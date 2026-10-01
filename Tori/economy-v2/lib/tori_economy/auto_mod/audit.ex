defmodule ToriEconomy.AutoMod.Audit do
  @moduledoc "Fact-only detection records in the existing moderation_cases table."
  require Logger
  alias ToriEconomy.Sql

  def record(guild_id, %{"rule" => rule} = detection) do
    # No ban, deletion or timeout occurred. The result must never imply otherwise.
    reason = "#{rule}: observed #{detection["observed_count"]} event(s); escalation #{detection["escalation"]}"

    Sql.query!(
      """
      INSERT INTO moderation_cases
        (guild_id,case_id,action,channel_id,target,reason,result,occurred_at,
         language,webhook_status,webhook_updated_at)
      VALUES ($1,$2,'AUTO_DETECT',$3,$4,$5,'OBSERVED',now(),'en','NOT_SENT',now())
      """,
      [
        to_string(guild_id),
        "AUTO-" <> Ecto.UUID.generate(),
        detection["channel_id"],
        detection["target_user_id"],
        reason
      ]
    )

    :ok
  rescue
    _ ->
      Logger.warning("AutoMod detection audit could not be persisted")
      {:error, :audit_unavailable}
  end
end
