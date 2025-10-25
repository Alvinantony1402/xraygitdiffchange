Feature: Product search

Background:
Given the user is on the search page

Scenario: Search finds matching products
When the user searches for "phone"
Then results related to "phone" should be shown

Scenario: Search with mixed case keyword
When the user searches for "LapTop"
Then results related to "LapTop" should be shown

Scenario: Search
When the user searches for "LapTop"
Then results related to "LapTop" should be shown

Scenario: Search1
When the user searches for "LapTop"
Then results related to "LapTop" should be shown