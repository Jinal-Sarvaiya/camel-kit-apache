"""Example skill helper. Run from the project root with the hook request as its argument."""
import json
import sys
from pathlib import Path


def review(request):
    decisions = request["decisions"]
    if not decisions:
        return {"request": request, "status": "needs-input",
                "question": "Which validation suite should cover these routes?"}

    suite = decisions[-1]["answer"]
    inventory = Path(request["artifacts"]["inventory"]["path"])
    rows = inventory.read_text(encoding="utf-8").splitlines()
    covered = {row.removeprefix(suite + "\t") for row in rows if row.startswith(suite + "\t")}
    missing = sorted(request["sources"].keys() - covered)
    evidence = (f"Suite {suite}: missing {', '.join(missing)}" if missing
                else f"Suite {suite} covers all {len(request['sources'])} scoped routes in {inventory}.")
    return {"request": request, "status": "failed" if missing else "passed",
            "checks": {"route-coverage": {"passed": not missing, "evidence": evidence}}}


if __name__ == "__main__":
    print(json.dumps(review(json.loads(Path(sys.argv[1]).read_text(encoding="utf-8")))))
