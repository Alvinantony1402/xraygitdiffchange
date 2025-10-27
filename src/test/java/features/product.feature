Feature: phone search

Background:
Given the user is on the search page

Scenario: product
When the user searches for "phone"
Then results related to "phone" should be shown

Scenario: product1
When the user searches for "LapTop"
Then results related to "LapTop" should be shown

Scenario: product2
When the user searches for "LapTop"
Then results related to "LapTop" should be shown
