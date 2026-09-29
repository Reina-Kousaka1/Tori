Logger.configure(level: :warning)
Code.require_file("support/test_schema.exs", __DIR__)
ToriEconomy.TestSchema.prepare!()
ExUnit.start()
