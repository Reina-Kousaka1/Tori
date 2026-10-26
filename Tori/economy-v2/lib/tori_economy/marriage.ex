defmodule ToriEconomy.Marriage do
  @moduledoc "Transactional relationship state over the shared Tori accounts."
  alias ToriEconomy.{Idempotency, Sql}

  @mutations ~w(marriage.propose marriage.accept marriage.decline marriage.cancel marriage.divorce)

  def execute(%{operation: "marriage.snapshot"} = request) do
    user = request.context["target_user_id"] || request.context["actor_user_id"]

    {:ok,
     %{
       "request_id" => request.request_id,
       "status" => "ok",
       "result" => %{"type" => "marriage", "relationship" => latest_for(user)}
     }}
  end

  def execute(%{operation: operation} = request) when operation in @mutations do
    Idempotency.run(request, fn -> change(request) end,
      skip_persist_errors: [
        "SELF_MARRIAGE",
        "RELATIONSHIP_CONFLICT",
        "NO_PENDING_PROPOSAL",
        "NOT_MARRIED"
      ]
    )
  end

  @doc "Read-only active relationship for the profile aggregator."
  def current_for(user) do
    case Sql.query!(
           """
           SELECT relationship_id,proposer_id,recipient_id,status,proposed_at,responded_at,ended_at
           FROM economy_v2_marriages
           WHERE status IN ('PENDING','MARRIED') AND (proposer_id=$1 OR recipient_id=$1)
           ORDER BY relationship_id DESC LIMIT 1
           """,
           [user]
         ).rows do
      [row] -> record(row, user)
      [] -> nil
    end
  end

  defp latest_for(user) do
    case Sql.query!(
           """
           SELECT relationship_id,proposer_id,recipient_id,status,proposed_at,responded_at,ended_at
           FROM economy_v2_marriages
           WHERE proposer_id=$1 OR recipient_id=$1
           ORDER BY CASE WHEN status IN ('PENDING','MARRIED') THEN 0 ELSE 1 END,
                    relationship_id DESC
           LIMIT 1
           """,
           [user]
         ).rows do
      [row] -> record(row, user)
      [] -> nil
    end
  end

  defp change(%{operation: "marriage.propose"} = request) do
    actor = request.context["actor_user_id"]
    partner = request.args["target_user_id"]

    if actor == partner do
      error("SELF_MARRIAGE")
    else
      lock_accounts(actor, partner)

      case Sql.query!(
             """
             SELECT relationship_id FROM economy_v2_marriages
             WHERE status IN ('PENDING','MARRIED')
               AND (proposer_id=ANY($1::text[]) OR recipient_id=ANY($1::text[]))
             LIMIT 1 FOR UPDATE
             """,
             [[actor, partner]]
           ).rows do
        [] ->
          [[id]] =
            Sql.query!(
              """
              INSERT INTO economy_v2_marriages(proposer_id,recipient_id,status)
              VALUES ($1,$2,'PENDING') RETURNING relationship_id
              """,
              [actor, partner]
            ).rows

          success(fetch(id, actor))

        _ ->
          error("RELATIONSHIP_CONFLICT")
      end
    end
  end

  defp change(%{operation: operation} = request)
       when operation in ["marriage.accept", "marriage.decline", "marriage.cancel"] do
    actor = request.context["actor_user_id"]
    role = if operation == "marriage.cancel", do: "proposer_id", else: "recipient_id"
    status = if operation == "marriage.accept", do: "MARRIED", else: "CANCELLED"

    case pending(actor, role) do
      {id, proposer, recipient} ->
        lock_accounts(proposer, recipient)

        case Sql.query!(
               """
               UPDATE economy_v2_marriages SET status=$2,responded_at=now(),
                 ended_at=CASE WHEN $2='CANCELLED' THEN now() ELSE NULL END
               WHERE relationship_id=$1 AND status='PENDING' AND #{role}=$3
               RETURNING relationship_id
               """,
               [id, status, actor]
             ).rows do
          [[^id]] -> success(fetch(id, actor))
          _ -> error("NO_PENDING_PROPOSAL")
        end

      nil ->
        error("NO_PENDING_PROPOSAL")
    end
  end

  defp change(%{operation: "marriage.divorce"} = request) do
    actor = request.context["actor_user_id"]

    case Sql.query!(
           """
           SELECT relationship_id,proposer_id,recipient_id
           FROM economy_v2_marriages
           WHERE status='MARRIED' AND (proposer_id=$1 OR recipient_id=$1)
           LIMIT 1
           """,
           [actor]
         ).rows do
      [[id, proposer, recipient]] ->
        lock_accounts(proposer, recipient)

        case Sql.query!(
               """
               UPDATE economy_v2_marriages SET status='DIVORCED',ended_at=now()
               WHERE relationship_id=$1 AND status='MARRIED'
               RETURNING relationship_id
               """,
               [id]
             ).rows do
          [[^id]] -> success(fetch(id, actor))
          _ -> error("NOT_MARRIED")
        end

      [] ->
        error("NOT_MARRIED")
    end
  end

  defp pending(actor, role) do
    rows =
      Sql.query!(
        """
        SELECT relationship_id,proposer_id,recipient_id
        FROM economy_v2_marriages WHERE status='PENDING' AND #{role}=$1
        LIMIT 1
        """,
        [actor]
      ).rows

    case rows do
      [[id, proposer, recipient]] -> {id, proposer, recipient}
      [] -> nil
    end
  end

  defp lock_accounts(first, second) do
    ids = Enum.sort([first, second])

    Enum.each(ids, fn user ->
      Sql.query!("INSERT INTO economy_accounts(user_id) VALUES ($1) ON CONFLICT DO NOTHING", [
        user
      ])

      Sql.query!("SELECT user_id FROM economy_accounts WHERE user_id=$1 FOR UPDATE", [user])
    end)
  end

  defp fetch(id, actor) do
    [row] =
      Sql.query!(
        """
        SELECT relationship_id,proposer_id,recipient_id,status,proposed_at,responded_at,ended_at
        FROM economy_v2_marriages WHERE relationship_id=$1
        """,
        [id]
      ).rows

    record(row, actor)
  end

  defp record([id, proposer, recipient, status, proposed, responded, ended], actor) do
    %{
      "id" => Integer.to_string(id),
      "proposer_user_id" => proposer,
      "recipient_user_id" => recipient,
      "partner_user_id" => if(actor == proposer, do: recipient, else: proposer),
      "status" => status,
      "proposed_at" => DateTime.to_iso8601(proposed),
      "responded_at" => timestamp(responded),
      "ended_at" => timestamp(ended)
    }
  end

  defp timestamp(nil), do: nil
  defp timestamp(value), do: DateTime.to_iso8601(value)

  defp success(relationship),
    do: %{"status" => "ok", "result" => %{"type" => "marriage", "relationship" => relationship}}

  defp error(code),
    do: %{"status" => "error", "error" => %{"code" => code, "retryable" => false}}
end
