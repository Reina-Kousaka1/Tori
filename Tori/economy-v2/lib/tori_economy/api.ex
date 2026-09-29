defmodule ToriEconomy.Api do
  @moduledoc "Private, versioned HTTP boundary. It is not a public or website API."
  import Plug.Conn
  require Logger

  alias ToriEconomy.{Contract, Dispatcher, Persona, Sql, WriteGate}
  alias ToriEconomy.Persona.Mood

  def init(opts), do: opts

  def call(conn, _opts) do
    case {conn.method, conn.request_path} do
      {"GET", "/internal/economy/v1/health"} ->
        try do
          Sql.query!("SELECT 1")
          reply(conn, 200, %{"status" => "ok"})
        rescue
          _ -> reply(conn, 503, error(nil, "SERVICE_UNAVAILABLE", true))
        end

      {"POST", "/internal/economy/v1/execute"} ->
        execute(conn)

      {"GET", "/internal/persona/v1/snapshot"} ->
        persona_snapshot(conn)

      {"POST", "/internal/persona/v1/events"} ->
        persona_event(conn)

      {"POST", "/internal/persona/v1/render"} ->
        persona_render(conn)

      _ ->
        reply(conn, 404, error(nil, "INVALID_INPUT", false))
    end
  end

  defp persona_snapshot(conn) do
    case authorized(conn) do
      :ok ->
        state = Persona.snapshot()
        reply(conn, 200, %{"identity" => "tori", "mood" => Atom.to_string(state.mood),
                           "intensity" => state.intensity, "season" => Atom.to_string(state.season)})

      _ -> reply(conn, 401, error(nil, "FORBIDDEN", false))
    end
  end

  defp persona_event(conn) do
    with :ok <- authorized(conn),
         :ok <- json_content_type(conn),
         {:ok, body, conn} <- read_body(conn, length: 1024),
         {:ok, %{"event" => name} = payload} <- Jason.decode(body),
         true <- map_size(payload) == 1,
         {:ok, event} <- Mood.event_from_name(name),
         {:ok, state} <- record_mood_event(event) do
      reply(conn, 200, %{"status" => "ok", "mood" => Atom.to_string(state.mood),
                         "intensity" => state.intensity})
    else
      {:error, "UNAUTHORIZED"} -> reply(conn, 401, error(nil, "FORBIDDEN", false))
      {:error, :unavailable} -> reply(conn, 503, error(nil, "SERVICE_UNAVAILABLE", true))
      _ -> reply(conn, 400, error(nil, "INVALID_INPUT", false))
    end
  end

  defp record_mood_event(event) do
    {:ok, Mood.record(event)}
  catch
    :exit, _ -> {:error, :unavailable}
  end

  defp persona_render(conn) do
    with :ok <- authorized(conn),
         :ok <- json_content_type(conn),
         {:ok, body, conn} <- read_body(conn, length: 2048),
         {:ok, %{"key" => key, "context" => context_name, "variables" => variables} = payload} <- Jason.decode(body),
         true <- map_size(payload) == 3 and is_binary(key) and byte_size(key) <= 100 and
                   Regex.match?(~r/^[a-z]+(?:\.[a-z_]+)*$/, key),
         true <- is_map(variables) and map_size(variables) <= 12,
         true <- Enum.all?(variables, fn {name, value} ->
           is_binary(name) and byte_size(name) <= 40 and
             ((is_binary(value) and byte_size(value) <= 256) or is_integer(value))
         end),
         {:ok, context} <- Persona.context_from_name(context_name) do
      phrase = Persona.render(key, variables, Persona.snapshot(context: context))
      reply(conn, 200, %{"status" => "ok", "text" => phrase})
    else
      {:error, "UNAUTHORIZED"} -> reply(conn, 401, error(nil, "FORBIDDEN", false))
      _ -> reply(conn, 400, error(nil, "INVALID_INPUT", false))
    end
  end

  defp execute(conn) do
    with :ok <- authorized(conn),
         :ok <- json_content_type(conn),
         {:ok, body, conn} <- read_body(conn, length: 32_768),
         {:ok, raw} <- Jason.decode(body),
         {:ok, request} <- Contract.validate(raw),
         :ok <- writes_allowed(request),
         {:ok, response} <- execute_request(request) do
      code = if get_in(response, ["error", "code"]) == "IDEMPOTENCY_CONFLICT", do: 409, else: 200
      reply(conn, code, response)
    else
      {:error, "UNAUTHORIZED"} ->
        reply(conn, 401, error(nil, "FORBIDDEN", false))

      {:error, "READ_ONLY"} ->
        reply(conn, 403, error(nil, "READ_ONLY", false))

      {:more, _body, conn} ->
        reply(conn, 413, error(nil, "INVALID_INPUT", false))

      {:error, "SERVICE_UNAVAILABLE"} ->
        reply(conn, 503, error(nil, "SERVICE_UNAVAILABLE", true))

      {:error, code} when code in ["INVALID_AMOUNT", "INVALID_TARGET"] ->
        reply(conn, 400, error(nil, code, false))

      _ ->
        reply(conn, 400, error(nil, "INVALID_INPUT", false))
    end
  rescue
    failure ->
      # No exception message, SQL text, request body or credential in logs.
      Logger.error("Economy request failed (#{inspect(failure.__struct__)})")
      reply(conn, 500, error(nil, "INTERNAL_ERROR", false))
  end

  # A running API is not permission to create a second wallet writer.
  defp writes_allowed(%{operation: operation})
       when operation in ["wallet.balance", "inventory.list", "shop.catalog", "wallet.leaderboard", "profile.snapshot", "market.product", "market.history", "shop.rotation", "progression.snapshot", "marketplace.browse"],
       do: :ok

  defp writes_allowed(request) do
    WriteGate.authorize(request.operation)
  end

  defp execute_request(request) do
    started = System.monotonic_time(:millisecond)

    result = Dispatcher.execute(request)

    status =
      case result do
        {:ok, %{"status" => "ok"}} -> "OK"
        {:ok, %{"error" => %{"code" => code}}} -> code
        {:error, code} -> code
        _ -> "INTERNAL_ERROR"
      end

    interaction_id = request.idempotency_key || "none"

    Logger.info(
      "economy request_id=#{request.request_id} interaction_id=#{interaction_id} " <>
        "operation=#{request.operation} status=#{status} duration_ms=#{System.monotonic_time(:millisecond) - started}"
    )

    result
  end

  defp authorized(conn) do
    expected = System.fetch_env!("TORI_ECONOMY_API_SECRET")

    case get_req_header(conn, "authorization") do
      ["Bearer " <> supplied] ->
        if byte_size(supplied) == byte_size(expected) and
             Plug.Crypto.secure_compare(supplied, expected),
           do: :ok,
           else: {:error, "UNAUTHORIZED"}

      _ ->
        {:error, "UNAUTHORIZED"}
    end
  end

  defp json_content_type(conn) do
    case get_req_header(conn, "content-type") do
      ["application/json" <> _] -> :ok
      _ -> {:error, "INVALID_INPUT"}
    end
  end

  defp error(request_id, code, retryable) do
    %{
      "request_id" => request_id,
      "status" => "error",
      "error" => %{"code" => code, "retryable" => retryable}
    }
  end

  defp reply(conn, status, body) do
    conn
    |> put_resp_content_type("application/json")
    |> send_resp(status, Jason.encode!(body))
  end
end
