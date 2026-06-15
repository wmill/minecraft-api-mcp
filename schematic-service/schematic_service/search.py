"""Elasticsearch access with a local fallback for development and tests."""

from __future__ import annotations

from collections import Counter
from typing import Any

import httpx


class SearchUnavailable(RuntimeError):
    """Raised when Elasticsearch cannot be reached."""


class SchematicSearchIndex:
    def __init__(self, elasticsearch_url: str, index_name: str):
        self.elasticsearch_url = elasticsearch_url.rstrip("/")
        self.index_name = index_name

    async def health(self) -> dict[str, Any]:
        try:
            async with httpx.AsyncClient(timeout=2.0) as client:
                response = await client.get(f"{self.elasticsearch_url}/_cluster/health")
                response.raise_for_status()
                return {"available": True, "elasticsearch": response.json()}
        except Exception as exc:
            return {"available": False, "error": repr(exc)}

    async def rebuild(self, docs: list[dict[str, Any]]) -> dict[str, Any]:
        mapping = {
            "mappings": {
                "properties": {
                    "schematic_id": {"type": "keyword"},
                    "title": {"type": "text"},
                    "description": {"type": "text"},
                    "keywords": {"type": "text"},
                    "keyword_tags": {"type": "keyword"},
                    "search_text": {"type": "text"},
                    "structure_type": {"type": "keyword"},
                    "style": {"type": "keyword"},
                    "size_category": {"type": "keyword"},
                    "has_interior": {"type": "boolean"},
                    "placeable": {"type": "boolean"},
                    "confidence": {"type": "float"},
                    "non_air_block_count": {"type": "integer"},
                }
            }
        }
        try:
            async with httpx.AsyncClient(timeout=30.0) as client:
                delete_response = await client.delete(f"{self.elasticsearch_url}/{self.index_name}")
                if delete_response.status_code not in (200, 404):
                    delete_response.raise_for_status()
                create_response = await client.put(f"{self.elasticsearch_url}/{self.index_name}", json=mapping)
                create_response.raise_for_status()

                bulk_lines: list[str] = []
                for doc in docs:
                    bulk_lines.append(
                        '{"index":{"_index":"%s","_id":"%s"}}' % (self.index_name, doc["schematic_id"])
                    )
                    bulk_lines.append(json_dumps(doc))
                bulk_body = "\n".join(bulk_lines) + "\n"
                response = await client.post(
                    f"{self.elasticsearch_url}/_bulk",
                    content=bulk_body,
                    headers={"content-type": "application/x-ndjson"},
                )
                response.raise_for_status()
                payload = response.json()
                if payload.get("errors"):
                    raise SearchUnavailable("Elasticsearch bulk indexing reported errors")
                refresh_response = await client.post(f"{self.elasticsearch_url}/{self.index_name}/_refresh")
                refresh_response.raise_for_status()
                return {"indexed": len(docs)}
        except Exception as exc:
            raise SearchUnavailable(repr(exc)) from exc

    async def top_tags(
        self,
        limit: int,
        filters: dict[str, Any],
    ) -> dict[str, Any]:
        size = max(1, min(limit, 100))
        filter_clauses = [{"term": {key: value}} for key, value in filters.items() if value is not None]
        body = {
            "query": {"bool": {"filter": filter_clauses}},
            "size": 0,
            "aggs": {
                "tags": {"terms": {"field": "keyword_tags", "size": size}},
                "structure_type": {"terms": {"field": "structure_type", "size": size}},
                "style": {"terms": {"field": "style", "size": size}},
                "size_category": {"terms": {"field": "size_category", "size": size}},
            },
        }

        try:
            async with httpx.AsyncClient(timeout=10.0) as client:
                response = await client.post(f"{self.elasticsearch_url}/{self.index_name}/_search", json=body)
                response.raise_for_status()
                return format_top_tags(response.json().get("aggregations", {}))
        except Exception as exc:
            raise SearchUnavailable(repr(exc)) from exc

    async def search(
        self,
        query: str,
        limit: int,
        filters: dict[str, Any],
    ) -> list[dict[str, Any]]:
        must: list[dict[str, Any]] = []
        if query:
            must.append(
                {
                    "multi_match": {
                        "query": query,
                        "fields": ["title^3", "keywords^2", "description", "search_text"],
                    }
                }
            )
        else:
            must.append({"match_all": {}})

        filter_clauses = [{"term": {key: value}} for key, value in filters.items() if value is not None]
        body = {
            "query": {"bool": {"must": must, "filter": filter_clauses}},
            "size": max(1, min(limit, 50)),
        }

        try:
            async with httpx.AsyncClient(timeout=10.0) as client:
                response = await client.post(f"{self.elasticsearch_url}/{self.index_name}/_search", json=body)
                response.raise_for_status()
                hits = response.json().get("hits", {}).get("hits", [])
                return [hit.get("_source", {}) | {"score": hit.get("_score")} for hit in hits]
        except Exception as exc:
            raise SearchUnavailable(repr(exc)) from exc


def json_dumps(value: Any) -> str:
    import json

    return json.dumps(value, separators=(",", ":"), ensure_ascii=False)


def format_top_tags(aggregations: dict[str, Any]) -> dict[str, Any]:
    return {
        "tags": [
            {"tag": bucket.get("key"), "count": bucket.get("doc_count", 0)}
            for bucket in aggregations.get("tags", {}).get("buckets", [])
        ],
        "facets": {
            name: [
                {"value": bucket.get("key"), "count": bucket.get("doc_count", 0)}
                for bucket in aggregations.get(name, {}).get("buckets", [])
            ]
            for name in ("structure_type", "style", "size_category")
        },
    }


def local_top_tags(
    docs: list[dict[str, Any]],
    limit: int,
    filters: dict[str, Any],
) -> dict[str, Any]:
    size = max(1, min(limit, 100))

    def matches(doc: dict[str, Any]) -> bool:
        for key, value in filters.items():
            if value is not None and doc.get(key) != value:
                return False
        return True

    matching = [doc for doc in docs if matches(doc)]
    tag_counts: Counter[str] = Counter()
    facet_counts: dict[str, Counter[str]] = {
        "structure_type": Counter(),
        "style": Counter(),
        "size_category": Counter(),
    }

    for doc in matching:
        tag_counts.update(tag for tag in doc.get("keyword_tags", []) if tag)
        for name, counter in facet_counts.items():
            value = doc.get(name)
            if value:
                counter[str(value)] += 1

    return {
        "tags": [{"tag": tag, "count": count} for tag, count in tag_counts.most_common(size)],
        "facets": {
            name: [{"value": value, "count": count} for value, count in counter.most_common(size)]
            for name, counter in facet_counts.items()
        },
    }


def local_search(
    docs: list[dict[str, Any]],
    query: str,
    limit: int,
    filters: dict[str, Any],
) -> list[dict[str, Any]]:
    query_terms = [term.lower() for term in query.split() if term.strip()]

    def matches(doc: dict[str, Any]) -> bool:
        for key, value in filters.items():
            if value is not None and doc.get(key) != value:
                return False
        return True

    def score(doc: dict[str, Any]) -> int:
        haystack = doc.get("search_text", "").lower()
        return sum(1 for term in query_terms if term in haystack)

    ranked = [doc | {"score": float(score(doc))} for doc in docs if matches(doc)]
    if query_terms:
        ranked = [doc for doc in ranked if doc["score"] > 0]
    ranked.sort(key=lambda doc: (doc.get("score") or 0, doc.get("confidence") or 0), reverse=True)
    return ranked[: max(1, min(limit, 50))]
