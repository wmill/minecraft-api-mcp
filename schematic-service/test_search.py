from schematic_service.search import local_search


def test_local_search_filters_and_scores():
    docs = [
        {
            "schematic_id": "1",
            "title": "Tower",
            "search_text": "stone medieval tower",
            "structure_type": "tower",
            "style": "medieval",
            "placeable": True,
            "confidence": 0.8,
        },
        {
            "schematic_id": "2",
            "title": "Cottage",
            "search_text": "wood rustic cottage",
            "structure_type": "building",
            "style": "rustic",
            "placeable": True,
            "confidence": 0.9,
        },
    ]

    results = local_search(docs, "stone tower", 10, {"structure_type": "tower", "placeable": True})

    assert [result["schematic_id"] for result in results] == ["1"]
    assert results[0]["score"] == 2.0
