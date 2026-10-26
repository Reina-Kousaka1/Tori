## Summary

<!-- What does this PR change and why? -->

## Type of change

- [ ] Feature
- [ ] Bug fix
- [ ] Refactor
- [ ] Database / migration
- [ ] Tests
- [ ] Documentation
- [ ] Infrastructure / CI
- [ ] Security

## Areas affected

- [ ] Java / JDA
- [ ] Elixir / Nostrum
- [ ] PostgreSQL
- [ ] Discord interactions
- [ ] Music / Lavalink
- [ ] Economy
- [ ] Shop / Marketplace
- [ ] Inventory
- [ ] Career / Profile
- [ ] Moderation / Anti-Raid
- [ ] Orders
- [ ] Presence
- [ ] Infrastructure / Docker

## Testing

Describe what was tested.

### Java

- [ ] Java tests
- [ ] Gradle build/check

### Elixir

- [ ] mix format --check-formatted
- [ ] mix compile --warnings-as-errors
- [ ] mix test

### Database

- [ ] PostgreSQL integration tests where applicable
- [ ] Migration path tested where applicable

### General

- [ ] Docker/build validation where applicable
- [ ] Security/dependency checks where applicable

## Database changes

- [ ] No database changes
- [ ] Additive migration included

If a migration exists:

Migration:
Fresh database tested:
Upgrade path tested:

IMPORTANT:
Existing applied production migrations are immutable.
Never edit an already-applied migration to change production state.

## Architecture

Confirm where relevant:

- [ ] Java remains responsible for Java-owned runtime functionality
- [ ] Elixir/Nostrum remains responsible for its existing domains
- [ ] PostgreSQL remains the authoritative persistent source of truth
- [ ] No duplicate wallet/inventory/progression state introduced
- [ ] Presence still has one authoritative writer
- [ ] Business logic remains separated from Discord presentation where applicable

## Security / privacy

- [ ] No Discord tokens committed
- [ ] No webhook credentials committed
- [ ] No database credentials committed
- [ ] No .env contents committed
- [ ] No secrets added to logs
- [ ] Permission-sensitive changes reviewed
- [ ] SQL remains parameterized where applicable

## Discord behavior

Commands/interactions affected:

Public/ephemeral behavior changed:

Command registration changed:

## Screenshots

<!-- Optional for visible Discord/UI changes -->

## Checklist

- [ ] Change is scoped and intentional
- [ ] Existing behavior has regression coverage where appropriate
- [ ] Documentation updated
