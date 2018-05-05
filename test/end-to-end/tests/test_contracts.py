import pytest
from unittest import TestCase
# from pages.openbounty.bounties import BountiesPage
# from pages.openbounty.activity import ActivityPage
from pages.thirdparty.github import get_contract_address
from pages.thirdparty.web3 import fund_issue

# from tests.basetestcase import BaseTestCase
from tests import test_data
#
# @pytest.mark.sanity
# class TestLogin(BaseTestCase):
#
#      def test_deploy_new_contract(self):
#
#         # Waiting for deployed contract; test_data.issue created here
#         self.github_org.create_new_bounty()
#         self.github_org.get_deployed_contract()
#
#         # Navigate and check top bounty in "Open bounties"
#         bounties_page = BountiesPage(self.driver_dev)
#         bounties_page.get_bounties_page()
#         titles = bounties_page.bounty_titles.find_elements()
#         assert titles[0].text == test_data.issue['title']
#
#      def test_new_claim(self):
#         self.github_dev.create_pr_git('test_branch_%s' % self.github_dev.time_now)
#         self.github_dev.open_pr_github('Fixes')
#
#         # check new claim in "Open bounties"
#         bounties_page = BountiesPage(self.driver_dev)
#         bounties_page.get_bounties_page()
#         bounties_page.check_bounty_claims_amount(test_data.issue['title'], '1 open claim')
#
#         # check new claim in "Activity"
#         activity_page = ActivityPage(self.driver_dev)
#         activity_page.get_activity_page()
#         activity_page.check_activity_is_presented('Submitted a claim for ', test_data.issue['title'])


@pytest.mark.sanity
class TestFunding(TestCase):
    def test_implement_bounty_funding(self):
        # issue_link = 'https://github.com/NewOrGanizationSOB/RepoSOB1_2/issues/145'
        # address = get_contract_address(issue_link)
        # self.assertEqual(address, '0x5e2ae47063c55902b160738d6c4c217638df6da7')
        private_key = test_data.config['Ethereum']['eth_private_key']
        eth_account = test_data.config['Ethereum']['eth_account']
        eth_provider = test_data.config['Ethereum']['eth_provider']
        fund_issue(private_key, eth_account, eth_provider, to='0x5E2aE47063c55902b160738d6C4C217638df6Da7')

        self.assertEqual(0, 1)
