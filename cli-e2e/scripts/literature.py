#!/usr/bin/env python3
"""Exercise an agent's bibliography/PDF workflow using the real CLI and worker."""

import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import uuid


def main():
    parser = argparse.ArgumentParser()
    for name in ("cli", "root-dir", "config", "graph"):
        parser.add_argument("--" + name, required=True)
    args = parser.parse_args()
    command = ["node", args.cli, "--root-dir", args.root_dir,
               "--config", args.config, "--graph", args.graph]

    def run(*argv, data=None, ok=True, output="json"):
        result = subprocess.run(command + ["--output", output, *argv],
                                input=None if data is None else json.dumps(data),
                                text=True, capture_output=True, timeout=60)
        assert (result.returncode == 0) == ok, (argv, result.stdout, result.stderr)
        if output != "json":
            return result.stdout
        payload = json.loads(result.stdout)
        assert (payload["status"] == "ok") == ok, payload
        return payload["data"] if ok else payload["error"]

    def imp(records, *extra, ok=True, page="Literature"):
        return run("literature", "import", "--target-page", page,
                   "--input", "-", *extra, data=records, ok=ok)

    def show(paper):
        return run("literature", "show", "--uuid", paper)

    def update(paper, patch, *extra, ok=True):
        return run("literature", "update", "--uuid", paper, "--input", "-",
                   *extra, data=patch, ok=ok)

    def query(expression):
        return run("query", "--query", expression)["result"]

    run("graph", "create")
    for command_name in ("list", "search", "show", "import", "update", "attach"):
        assert command_name in run("literature", "--help", output="human")
    assert "literature import" in run("example", "literature", output="human")
    assert "literature" in run("skill", "show", output="human")

    manual = str(uuid.uuid4())
    full_title = "Synthetic manual paper " + "complete title " * 25
    records = [{"title": "Synthetic DOI paper", "doi": "10.1234/cli-example",
                "authors": "Alice Example", "date": "2026"},
               {"title": "Synthetic arXiv paper", "arxiv": "2601.00002v1"},
               {"title": full_title, "uuid": manual}]
    items = imp(records)["items"]
    assert [item["input-index"] for item in items] == [0, 1, 2], items
    assert all(item["status"] == "created" for item in items), items
    doi, arxiv, actual_manual = [item["paper-uuid"] for item in items]
    assert actual_manual == manual
    again = imp(records)["items"]
    assert [item["paper-uuid"] for item in again] == [doi, arxiv, manual]
    assert all(item["status"] == "existing" for item in again), again
    preview = imp(records[0], "--dry-run", page="Uncreated duplicate")["items"][0]
    assert preview["status"] == "existing" and preview["paper-uuid"] == doi, preview
    assert query('[:find ?e . :where [?e :block/title "Uncreated duplicate"]]') is None
    first = run("literature", "list", "--limit", "2")
    assert first["has-more"] and len(first["items"]) == 2, first
    second = run("literature", "list", "--limit", "2", "--before", str(first["next-before"]))
    assert not second["has-more"] and len(second["items"]) == 1, second
    assert {v["uuid"] for v in first["items"] + second["items"]} == {doi, arxiv, manual}
    assert show(manual)["title"] == full_title.strip()
    found = run("literature", "search", "--content", "Synthetic DOI paper")
    assert doi in [v["uuid"] for v in found["items"]], found
    assert isinstance(found["truncated"], bool), found
    assert "uuid" in run("literature", "list", output="human")
    assert ":status :ok" in run("literature", "list", output="edn")

    before = show(manual)
    assert update(manual, {"authors": "Bob Example", "citekey": "example2026"}, "--dry-run")["status"] == "planned"
    assert show(manual) == before
    assert update(manual, {"authors": "Bob Example", "citekey": "example2026"})["status"] == "updated"
    assert update(manual, {"authors": "Bob Example"})["status"] == "unchanged"
    update(manual, {"authors": None})
    assert show(manual)["authors"] is None and show(manual)["citekey"] == "example2026"
    assert update(manual, {"doi": "10.1234/cli-example"}, ok=False)["reason"] == "identifier-conflict"
    assert show(doi)["authors"] == "Alice Example"
    invalid_identifier = update(manual, {"doi": "invalid"}, ok=False)
    assert "Invalid DOI" in invalid_identifier["detail"], invalid_identifier
    assert show(manual)["doi"] is None
    invalid = imp([{"title": "Must not be written"}, {"title": "Invalid", "unsupported": 1}], ok=False)
    assert invalid["code"] == "invalid-options", invalid
    assert query('[:find ?e . :where [?e :block/title "Must not be written"]]') is None
    planned = imp({"title": "Planned only"}, "--dry-run", page="Uncreated destination")
    assert planned["items"][0]["status"] == "planned", planned
    assert query('[:find ?e . :where [?e :block/title "Uncreated destination"]]') is None
    invalid_preview = imp({"title": "Invalid preview", "doi": "invalid"},
                          "--dry-run", page="Invalid preview destination", ok=False)
    assert invalid_preview["items"][0]["status"] == "blocked", invalid_preview
    assert query('[:find ?e . :where [?e :block/title "Invalid preview destination"]]') is None
    partial = imp([{"title": "Conflict", "doi": "10.1234/cli-example", "arxiv": "2601.00002"},
                   {"title": "Successful sibling", "uuid": str(uuid.uuid4())}], ok=False)
    assert partial["code"] == "literature-partial-import", partial
    results = partial["items"]
    assert results[0]["status"] == "blocked" and results[0]["input-index"] == 0, results
    assert results[1]["status"] == "created" and results[1]["paper-uuid"], results

    pdf = Path(args.root_dir) / "synthetic-paper.pdf"
    payload = b"%PDF-1.4\n1 0 obj\n<< /Type /Catalog >>\nendobj\n%%EOF\n"
    pdf.write_bytes(payload)
    linked = run("literature", "attach", "--uuid", manual, "--path", str(pdf))
    asset = linked["asset-uuid"]
    assert linked["status"] == "linked" and linked["paper-uuid"] == manual, linked
    repeated = run("literature", "attach", "--uuid", manual, "--path", str(pdf))
    assert repeated["status"] == "already-linked" and repeated["asset-uuid"] == asset, repeated
    existing = run("literature", "attach", "--uuid", manual, "--asset-uuid", asset)
    assert existing["status"] == "already-linked", existing
    rejected = run("literature", "attach", "--uuid", arxiv, "--asset-uuid", asset, ok=False)
    assert rejected["asset-uuid"] == asset and rejected["paper-uuid"] == arxiv, rejected
    saved = show(manual)
    attachment = saved["assets"][0]
    assert "synthetic-paper.pdf" in attachment["filenames"], attachment
    assert attachment["local-status"] == "available", attachment
    local = Path(attachment["local-path"])
    assert local.read_bytes() == payload
    assert attachment["checksum"] == hashlib.sha256(payload).hexdigest(), attachment
    local.rename(local.with_suffix(".held"))
    try:
        missing = show(manual)["assets"][0]
        assert missing["local-status"] == "missing" and missing["local-path"] is None, missing
    finally:
        local.with_suffix(".held").rename(local)
    assert len(show(manual)["assets"]) == 1
    assert manual in [v["uuid"] for v in run("literature", "search", "--content", "synthetic-paper")["items"]]
    assert run("literature", "attach", "--uuid", manual, "--path", str(pdf),
               "--asset-uuid", asset, ok=False)["code"] == "invalid-options"
    patch = Path(args.root_dir) / "patch.json"
    patch.write_text(json.dumps({"publication": "Example Journal"}))
    run("literature", "update", "--uuid", manual, "--input", str(patch))
    saved = show(manual)
    assert saved["publication"] == "Example Journal"
    run("server", "stop")
    assert show(manual) == saved
    run("graph", "validate")
    run("remove", "block", "--uuid", results[1]["paper-uuid"])
    assert run("literature", "show", "--uuid", results[1]["paper-uuid"], ok=False)["reason"] == "paper-unavailable"
    hidden = imp({"title": "Hidden descendant"}, page="Hidden literature")["items"][0]["paper-uuid"]
    run("remove", "page", "--page", "Hidden literature")
    assert run("literature", "show", "--uuid", hidden, ok=False)["reason"] == "paper-unavailable"
    assert hidden not in [v["uuid"] for v in run("literature", "list")["items"]]
    imp([{"title": "Truncated bibliography example " + str(i)} for i in range(11)])
    truncated = run("literature", "search", "--content", "Truncated bibliography")
    assert truncated["truncated"] and len(truncated["items"]) == 10, truncated
    run("server", "stop")
    print("literature agent workflow passed")


if __name__ == "__main__":
    main()
