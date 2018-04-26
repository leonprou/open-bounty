(ns commiteth.eth.core
  (:require [clojure.data.json :as json]
            [org.httpkit.client :refer [post]]
            [commiteth.db.issues :as issues]
            [clojure.java.io :as io]
            [commiteth.config :refer [env]]
            [clojure.string :refer [join]]
            [taoensso.tufte :as tufte :refer (defnp p profiled profile)]
            [clojure.tools.logging :as log]
            [clojure.string :as str]
            [pandect.core :as pandect]
            [mount.core :as mount]
            [clj-time.core :as t]
            [commiteth.util.util :refer [json-api-request]])
  (:import [org.web3j
            protocol.Web3j
            protocol.http.HttpService
            protocol.core.DefaultBlockParameterName
            protocol.core.methods.response.EthGetTransactionCount
            protocol.core.methods.request.RawTransaction
            utils.Numeric
            crypto.Credentials
            crypto.TransactionEncoder
            crypto.WalletUtils]))

(defn eth-rpc-url [] (env :eth-rpc-url "http://localhost:8545"))
(defn eth-account [] (:eth-account env))
(defn eth-password [] (:eth-password env))
(defn gas-estimate-factor [] (env :gas-estimate-factor 1.0))
(defn auto-gas-price? [] (env :auto-gas-price false))
(defn offline-signing? [] (env :offline-signing true))

(def web3j-obj
  (delay (Web3j/build (HttpService. (eth-rpc-url)))))

(def creds-obj (atom nil))

(defn wallet-file-path []
  (env :eth-wallet-file))

(defn wallet-password []
  (env :eth-password))

(defn creds []
  (or @creds-obj
      (let [password  (wallet-password)
            file-path (wallet-file-path)]
        (if (and password file-path)
          (swap! creds-obj
                 (constantly (WalletUtils/loadCredentials
                               password
                               file-path)))
          (throw (ex-info "Make sure you provided proper credentials in appropriate resources/config.edn"
                          {:password password :file-path file-path}))))))

(defn get-nonce []
  (.. (.ethGetTransactionCount @web3j-obj 
                               (env :eth-account) 
                               DefaultBlockParameterName/PENDING)
      sendAsync
      get
      getTransactionCount))


(defprotocol TxTracker
  (try-reserve-nonce [this]
    "Fetch current nonce via eth_getTransactionCount
    and either return it if it is not in use yet,
    or throw an exception")
  (drop-nonce [this nonce]
    "If tx execution returned with an error,
    release previously reserved nonce")
  (track-tx [this tx-info]
    "Record tx data after successful submission")
  (untrack-tx [this tx-info]
    "Mark tx as successfully closed")
  (prune-txs [this txs]
    "Release nonces related to txs param
    and the ones that have expired (haven't been marked as closed after a certain timeout)")
  )

(defrecord SequentialTxTracker [current-tx]
  TxTracker
  (try-reserve-nonce [this]
    (let [nonce (get-nonce)]
      (if (or (nil? @current-tx)
              (> nonce (:nonce @current-tx)))
        (:nonce (reset! current-tx {:nonce nonce}))
        (throw (Exception. (str "Attempting to re-use old nonce" nonce))))))
  (drop-nonce [this nonce]
    (reset! current-tx nil))
  (track-tx [this tx-info]
    (reset! current-tx tx-info))
  (untrack-tx [this tx-info]
    (reset! current-tx nil))
  (prune-txs [this unmined-txs]
    (when (or ((set (map :tx-hash unmined-txs)) (:tx-hash @current-tx))
            (and (:timestamp @current-tx)
               (t/before? (:timestamp @current-tx) (t/minus (t/now) (t/minutes 5)))))
      (log/errorf "Current nonce unmined for 5 minutes, force reset. Tx hash: %s, type: %s" 
                  (:tx-hash @current-tx) (:type @current-tx))
      (reset! current-tx nil)))

  )
(def tx-tracker (SequentialTxTracker. (atom nil)))

(defn track-tx! 
  "Store tx data in tx-tracker and DB"
  [{:keys [issue-id tx-hash type]
                  :as tx-info}]
  (track-tx tx-tracker tx-info)
  (issues/save-tx-info! issue-id tx-hash type))
  
(defn untrack-tx! 
  "Mark tx data stored in tx-tracker and DB as closed"
  [{:keys [issue-id result type]
                    :as tx-info}]
  (untrack-tx tx-tracker tx-info)
  (issues/save-tx-result! issue-id result type))

(defn prune-txs! [unmined-txs]
  "Release nonces related to unmined txs,
   and set relevant DB fields to null thereby
   marking them as candidates for re-execution"
  (doseq [{issue-id :issue_id
           tx-hash :tx_hash
           type :type} unmined-txs]
    (log/infof "issue %s: resetting tx operation: %s for hash: %s" issue-id type tx-hash)
    (issues/save-tx-info! issue-id nil type))
  (prune-txs tx-tracker unmined-txs))

 
(defn get-signed-tx [gas-price gas-limit to data nonce]
  "Create a sign a raw transaction.
  'From' argument is not needed as it's already
  encoded in credentials.
  See https://web3j.readthedocs.io/en/latest/transactions.html#offline-transaction-signing"
  (log/infof "Signing TX: nonce: %s, gas-price: %s, gas-limit: %s, data: %s"
             nonce gas-price gas-limit data)
  (-> (RawTransaction/createTransaction nonce gas-price gas-limit to data)
      (TransactionEncoder/signMessage (creds))
      (Numeric/toHexString)))


(defn eth-gasstation-gas-price
  "Get max of average and average_calc from gas station and use that
   as gas price. average_calc is computed from a larger time period than average,
   so the idea is to account for both temporary dips (when average_calc > average)
   and temporary rises (average_calc < average) of gas price"
  []
  (let [data (json-api-request "https://ethgasstation.info/json/ethgasAPI.json")
        avg-price (max
                    (-> (get data "average")
                        bigint)
                    (-> (get data "average_calc")
                        bigint))
        avg-price-gwei (/ avg-price (bigint 10))]
    (->> (* (bigint (Math/pow 10 9)) avg-price-gwei) ;; for some reason the API returns 10x gwei price
        .toBigInteger)))


(defn gas-price-from-config []
  (-> (:gas-price env 20000000000) ;; 20 gwei default
      str
      BigInteger.))

(defn gas-price
  []
  (if (auto-gas-price?)
    (try
      (eth-gasstation-gas-price)
      (catch Throwable t
        (log/error "Failed to get gas price with ethgasstation API" t)
        (gas-price-from-config)))
    (gas-price-from-config)))

(defn safe-read-str [s]
  (if (nil? s)
    (do
      (log/error "JSON response is nil")
      nil)
    (try
      (json/read-str s :key-fn keyword)
      (catch Exception ex
        (log/error ex "Exception when parsing json string:" s)))))

(def req-id-tracker
  ;; HACK to ensure previous random-number approach doesn't lead to
  ;; unintended collisions
  (atom 0))

(defn eth-rpc
  [{:keys [method params internal-tx-id]}]
  {:pre [(string? method) (some? params)]}
  (let [[type-kw issue-id] internal-tx-id
        tx-id-str (str type-kw "-" issue-id)
        request-id (swap! req-id-tracker inc)
        body       {:jsonrpc "2.0"
                    :method  method
                    :params  params
                    :id      request-id}
        options  {:headers {"content-type" "application/json"}
                  :body (json/write-str body)}
        response  @(post (eth-rpc-url) options)
        result   (safe-read-str (:body response))]
    (when internal-tx-id
      (log/infof "%s: eth-rpc %s" tx-id-str method))
    (log/debugf "%s: eth-rpc req(%s) body: %s" tx-id-str request-id body)
    (if tx-id-str
      (log/infof "%s: eth-rpc req(%s) result: %s" tx-id-str request-id result)
      (log/debugf "no-tx-id: eth-rpc req(%s) result: %s" request-id result))
    (cond
      ;; Ignore any responses that have mismatching request ID
      (not= (:id result) request-id)
      (throw (ex-info "Geth returned an invalid json-rpc request ID, ignoring response"))

      ;; If request ID matches but contains error, throw
      (:error result)
      (throw
       (ex-info (format "%s: Error submitting transaction via eth-rpc %s"
                        (or tx-id-str "(no-tx-id)") (:error result))
                (:error result)))

      :else
      (:result result))))

(defn hex->big-integer
  [hex]
  (new BigInteger (subs hex 2) 16))

(defn integer->hex
  "Convert integer to 0x prefixed hex string. Works with native ints and BigInteger"
  [n]
  (str "0x" (.toString (BigInteger. (str n)) 16)))


(defn strip-decimal
  [s]
  (str/replace s #"\..*" ""))


(defn adjust-gas-estimate
  "Multiply given estimate by factor"
  [gas]
  (let [factor (gas-estimate-factor)]
    (if (= 1.0 factor)
      gas
      (let [adjust (fn [x] (+ x (* factor x)))]
        (-> gas
            hex->big-integer
            adjust
            strip-decimal
            read-string
            integer->hex)))))

(defn estimate-gas
  [from to value & [params]]
  (let [geth-estimate (eth-rpc
                       {:method "eth_estimateGas"
                        :params [(merge params {:from  from
                                                :to    to
                                                :value value})]})
        adjusted-gas (adjust-gas-estimate geth-estimate)]

    (log/debug "estimated gas (geth):" geth-estimate)
    (log/debug "bumped estimate:" adjusted-gas)
    adjusted-gas))


(defn from-wei
  [wei]
  (/ wei 1000000000000000000))

(defn eth->wei
  [eth]
  (biginteger (* eth 1000000000000000000)))


(defn hex->eth
  [hex digits]
  (->> hex hex->big-integer from-wei double (format (str "%." digits "f"))))

(defn get-balance-hex
  [account]
  (eth-rpc {:method "eth_getBalance"
            :params [account "latest"]}))

(defn get-balance-wei
  [account]
  (hex->big-integer (get-balance-hex account)))

(defn get-balance-eth
  [account digits]
  (p :get-balance-eth
     (hex->eth (get-balance-hex account) digits)))

(defn- format-param
  [param]
  (if (number? param)
    (format "%064x" param)
    (clojure.string/replace (format "%64s" (subs param 2)) " " "0")))

(defn get-transaction-receipt
  [hash]
  (eth-rpc {:method "eth_getTransactionReceipt"
            :params [hash]}))

(defn format-call-params
  [method-id & params]
  (let [params (join (map format-param params))]
    (str method-id params)))

(defn call
  [contract method-id & params]
  (let [data (apply format-call-params method-id params)]
    (eth-rpc {:method "eth_call"
              :params [{:to contract :data data} "latest"]})))

(defn construct-params
  [{:keys [from contract method-id params gas-price]}]
  (let [data (apply format-call-params method-id params)
        value (format "0x%x" 0)]
    (cond-> {:data data
             :from  from
             :value value}
      gas-price
      (merge {:gasPrice (integer->hex gas-price)})
      contract
      (merge {:to contract}))))

(defmulti execute (fn [arg] (offline-signing?)))


(defmethod execute true
  [{:keys [from contract method-id gas-limit params internal-tx-id]
    :as args}]
  {:pre [(string? method-id)]}
  (let [[type-kw issue-id] internal-tx-id
        nonce (try-reserve-nonce tx-tracker)
        gas-price (gas-price)
        params (construct-params (assoc args :gas-price gas-price))
        gas (or gas-limit (estimate-gas from contract (:value params) params))
        params (get-signed-tx (biginteger gas-price)
                              (hex->big-integer gas)
                              contract
                              (:data params)
                              nonce)
        tx-hash (try
                  (eth-rpc
                  {:method "eth_sendRawTransaction"
                   :params [params]
                   :internal-tx-id internal-tx-id})
                  (catch Throwable ex
                    (drop-nonce tx-tracker nonce)
                    (throw ex)))]
    {:tx-hash tx-hash
     :issue-id issue-id
     :type type-kw
     :nonce nonce
     :timestamp (t/now)}))

(defmethod execute false
  [{:keys [from contract method-id gas-limit params internal-tx-id]
    :as args}]
  {:pre [(string? method-id)]}
  (let [[type-kw issue-id] internal-tx-id
        gas-price (gas-price)
        params (construct-params (assoc args :gas-price gas-price))
        gas (or gas-limit (estimate-gas from contract (:value params) params))
        params (assoc params :gas gas)
        tx-hash (eth-rpc
                  {:method "personal_sendTransaction"
                   :params [params (eth-password)]
                   :internal-tx-id internal-tx-id})]
    {:tx-hash tx-hash
     :type type-kw
     :timestamp (t/now)
     :issue-id issue-id}
    ))


(defn hex-ch->num
  [ch]
  (read-string (str "0x" ch)))

(defn strip-0x
  [x]
  (str/replace x #"(?i)^0x" ""))

(defn upper-ch [ch]
  (first (str/upper-case ch)))

(defn lower-ch [ch]
  (first (str/lower-case ch)))

(defn sha3 [x]
  (pandect/keccak-256 x))


(defn sig->method-id [signature]
  (let [s (apply str (take 8 (sha3 signature)))]
    (str "0x" s)))

(defn event-sig->topic-id [signature]
  (let [s (sha3 signature)]
    (str "0x" s)))

(defn valid-address?
  "Validate given ethereum address. Checksum validation is performed
  and input is case-sensitive"
  ([address]
   (valid-address? address false))
  ([address checksum-validate?]
   (log/debug "valid-address?" address)
   ;; logic based on
   ;;  https://github.com/cilphex/ethereum-address/blob/master/index.js
   (and (boolean (re-matches #"(?i)^(0x)?[0-9a-f]{40}$" address))
        (if checksum-validate?
          (let [addr (strip-0x address)
               hash (sha3 (str/lower-case addr))]
           (log/debug "address hash" hash "addr" addr)
           (->>
            (map-indexed (fn [idx _]
                           (let [hash-ch-int (hex-ch->num (nth hash idx))
                                 ch (nth addr idx)
                                 ch-lower (lower-ch ch)
                                 ch-upper (upper-ch ch)]
                             (or (and (> hash-ch-int 7)
                                      (not= ch-upper ch))
                                 (and (<= hash-ch-int 7)
                                      (not= ch-lower ch)))))
                         addr)
            (filter true?)
            (empty?)))
          true))))

(mount/defstate
  eth-core
  :start
  (do
    (swap! creds-obj (constantly nil))
    (log/info "eth/core started"))
  :stop
  (log/info "eth/core stopped"))


