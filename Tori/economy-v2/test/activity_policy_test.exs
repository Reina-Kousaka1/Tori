defmodule ToriEconomy.Activity.PolicyTest do
  use ExUnit.Case, async: true
  alias ToriEconomy.Activity.Policy

  test "tier and reward bounds match the Java gather policy" do
    assert Policy.tier(35) == 1
    assert Policy.tier(40) == 1
    assert Policy.tier(130) == 3
    assert Policy.tier(2500) == 8
    assert Policy.credits(3, 0) == 30
    assert Policy.credits(3, 47) == 77
  end

  test "drop threshold boundaries match legacy fish mine and chop" do
    assert Policy.drop("fish", 5, 1) == "shark"
    assert Policy.drop("fish", 1, 1) == "fish_crate"
    assert Policy.drop("mine", 8, 1) == "sparkle_fragment"
    assert Policy.drop("mine", 4, 6) == "moon_rune"
    assert Policy.drop("chop", 1, 9) == "pear"
    assert Policy.drop("chop", 8, 0) == "sparkle_fragment"
  end
end
