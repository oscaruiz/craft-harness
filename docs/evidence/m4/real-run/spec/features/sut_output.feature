Feature: sut.sh produces the expected answer
  As the test harness
  I want sut.sh to print exactly 42
  So that test.sh exits 0

  @SUT-1
  Scenario: sut.sh prints 42 to stdout
    Given the script sut.sh
    When I run "bash ./sut.sh"
    Then the stdout equals "42"

  @SUT-2
  Scenario: running sut.sh succeeds
    Given the script sut.sh
    When I run "bash ./sut.sh"
    Then the command exits with status 0

  @SUT-3
  Scenario: the test harness passes
    Given sut.sh prints exactly 42
    When I run "./test.sh"
    Then the command exits with status 0

  @SUT-4
  Scenario: output has no extra content
    Given the script sut.sh
    When I run "bash ./sut.sh" and capture stdout via command substitution
    Then the captured value equals "42" with no leading or trailing whitespace
