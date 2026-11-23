---
name: camel-review-coverage
description: Check a validation suite's route inventory against a workflow hook request.
user_invocable: false
---

When invoked for the coverage hook, save the supplied request object as JSON in a temporary file.
From the project root, run `python3 <this skill directory>/guides/coverage.py <request file>`.
Return its JSON result to the caller for submission to Kit. The caller handles developer questions.
