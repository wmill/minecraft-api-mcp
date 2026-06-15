from schematic_service.search import local_search, local_top_tags


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


def test_local_top_tags_filters_placeable_and_caps_results():
    docs = [
        {
            "schematic_id": "1",
            "keyword_tags": ["stone", "tower"],
            "structure_type": "tower",
            "style": "medieval",
            "size_category": "medium",
            "placeable": True,
        },
        {
            "schematic_id": "2",
            "keyword_tags": ["stone", "cottage"],
            "structure_type": "building",
            "style": "rustic",
            "size_category": "small",
            "placeable": True,
        },
        {
            "schematic_id": "3",
            "keyword_tags": ["castle"],
            "structure_type": "castle",
            "style": "medieval",
            "size_category": "large",
            "placeable": False,
        },
    ]

    result = local_top_tags(docs, 1, {"placeable": True})

    assert result["tags"] == [{"tag": "stone", "count": 2}]
    assert result["facets"]["style"] == [{"value": "medieval", "count": 1}]
    assert result["facets"]["structure_type"] == [{"value": "tower", "count": 1}]
    assert result["facets"]["size_category"] == [{"value": "medium", "count": 1}]
