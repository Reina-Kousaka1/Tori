ARG ELIXIR_IMAGE=hexpm/elixir:1.18.5-erlang-27.3.4.18-debian-bookworm-20260918-slim

FROM ${ELIXIR_IMAGE} AS build
WORKDIR /src
ENV MIX_ENV=prod

RUN mix local.hex --force && mix local.rebar --force
COPY mix.exs mix.lock ./
RUN mix deps.get --only prod
COPY lib ./lib
RUN mix compile --warnings-as-errors && mix release

FROM ${ELIXIR_IMAGE} AS runtime
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY --from=build --chown=10001:10001 /src/_build/prod/rel/tori_economy/ ./
USER 10001:10001
ENV HOME=/tmp
EXPOSE 4001
HEALTHCHECK --interval=20s --timeout=5s --retries=5 --start-period=30s \
    CMD curl --fail --silent --show-error http://127.0.0.1:4001/internal/economy/v1/health || exit 1
CMD ["/app/bin/tori_economy", "start"]
