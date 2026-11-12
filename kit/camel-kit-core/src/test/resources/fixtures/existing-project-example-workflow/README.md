# Workflow Example: Adding Orders API

Demonstrates adding `/api/orders` endpoint to an existing Camel application.

This workflow example is kept separate from the `existing-project-spring-boot` fixture to prevent its files from being discovered during `camel-kit init`.

## Structure

```
existing-project-example-workflow/
├── design/
│   └── design-spec.md          # Brainstorm output
├── after/
│   ├── order-routes.yaml       # New route
│   ├── OrderApiTest.java       # New test  
│   └── validation-report.md    # Validation results
└── README.md                   # This file
```

## Workflow Phases

1. **Design** (`design/design-spec.md`) - Captures what to add, integration points, constraints
2. **Execute** (`after/*.yaml`, `after/*.java`) - Implementation artifacts
3. **Validate** (`after/validation-report.md`) - Baseline preservation evidence

## Using This Example

**Important:** Copy both the fixture and this example to temporary locations:

```bash
# Copy fixture and example to temp location
cp -r kit/camel-kit-core/src/test/resources/fixtures/existing-project-spring-boot /tmp/example-test
cp -r kit/camel-kit-core/src/test/resources/fixtures/existing-project-example-workflow /tmp/

# Navigate to temp fixture
cd /tmp/example-test

# Run baseline tests (should pass 3)
mvn test

# Copy the new files from the example
cp /tmp/existing-project-example-workflow/after/order-routes.yaml src/main/resources/camel/
cp /tmp/existing-project-example-workflow/after/OrderApiTest.java src/test/java/org/apache/camel/kit/test/fixture/

# Run tests again - should pass 7 (3 baseline + 4 new)
mvn test
```

## Evidence

The validation report shows:
- 3/3 baseline tests still pass
- 4/4 new tests pass
- Protected files unchanged (checksums match)
- Only approved files added

Full documentation: `docs/modules/ROOT/pages/existing-project.adoc`
