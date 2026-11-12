# Design Specification: Add Orders REST Endpoint

**Created:** 2026-11-12  
**Status:** Approved  
**Workflow Phase:** Design (Brainstorm output)

## User Request

"Add a new REST endpoint `/api/orders` that returns a list of orders in JSON format, similar to the existing `/api/users` endpoint."

## Requested Additions

- New route: `get-orders-api`
- REST endpoint: `GET /api/orders`
- Response format: JSON array of order objects
- Pattern: Follow the same structure as existing `get-users-api` route

## Integration Points

| New Route | Connects To | Mechanism |
|-----------|-------------|-----------|
| get-orders-api | Existing REST infrastructure | Uses same `rest:` DSL and platform-http component |
| get-orders-api | Logging infrastructure | Uses `log:` component like existing routes |

## Affected Files

### Files to CREATE
- `src/main/resources/camel/order-routes.yaml` - New route definition  
- `src/test/java/org/apache/camel/kit/test/fixture/OrderApiTest.java` - Integration test

### Files to MODIFY
- None (new capability is additive only)

## Unchanged Behavior Constraints

### Protected Routes
- `get-users-api` - MUST remain unchanged
- `health-check` - MUST remain unchanged  
- `scheduled-task` - MUST remain unchanged

### Protected Configuration
- `application.properties` - MUST NOT modify
- `pom.xml` - MUST NOT modify (all components already present)

### Protected Tests
- `ExistingRoutesTest` - MUST continue to pass without modification

## Dependencies

No new dependencies required:
- `camel-rest-starter` (already in pom.xml)
- `camel-platform-http-starter` (already in pom.xml)
- `camel-yaml-dsl-starter` (already in pom.xml)

## Success Criteria

1. New endpoint `/api/orders` returns valid JSON array
2. New route `get-orders-api` is loaded and started
3. All 3 existing tests continue to pass (baseline preserved)
4. New test `OrderApiTest` passes
5. No modifications to existing routes or configuration
6. Camel version remains 4.8.0
7. Spring Boot version remains 3.3.4
