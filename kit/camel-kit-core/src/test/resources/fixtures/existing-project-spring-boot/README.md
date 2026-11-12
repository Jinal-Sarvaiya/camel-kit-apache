# Existing Project Spring Boot Fixture

Test fixture for existing-project workflow (issue #167).

## What This Is

A working Camel 4.8.0 Spring Boot 3.3.4 application with:
- 3 YAML routes in `src/main/resources/camel/`
- 3 baseline tests in `ExistingRoutesTest.java`
- Workflow example in sibling directory `../existing-project-example-workflow/`

## Running the Baseline Tests

```bash
cd kit/camel-kit-core/src/test/resources/fixtures/existing-project-spring-boot
mvn test
```

Expected: 3 tests pass.

## Workflow Example

See `../existing-project-example-workflow/` for a complete demonstration of adding a new `/api/orders` endpoint.

**To try the example:** Copy this fixture to a temporary directory first to preserve the baseline:

```bash
cp -r kit/camel-kit-core/src/test/resources/fixtures/existing-project-spring-boot /tmp/test-fixture
cp -r kit/camel-kit-core/src/test/resources/fixtures/existing-project-example-workflow /tmp/
cd /tmp/test-fixture
# Then follow instructions in /tmp/existing-project-example-workflow/README.md
```

The example shows:
- Design specification (what to add)
- Implementation (route + test)
- Validation report (baseline preserved, new capability works)

## Documentation

Complete guide: `docs/modules/ROOT/pages/existing-project.adoc`
