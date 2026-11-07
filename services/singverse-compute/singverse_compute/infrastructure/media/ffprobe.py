"""Normalize metadata from bounded reference-based FFprobe extraction."""
def normalize(payload):
    tags = {}
    # Container tags take precedence over stream tags; first nonempty value wins.
    sources = [payload.get('format', {})] + payload.get('streams', [])
    for source in sources:
        for key, value in source.get('tags', {}).items():
            if isinstance(value, str) and value.strip():
                tags.setdefault(key.lower(), value.strip()[:500])
    artists = tags.get('artist') or tags.get('artists')
    return {'title': tags.get('title'), 'artists': [p.strip()[:200] for p in artists.split(';') if p.strip()][:32] if artists else [],
            'album': tags.get('album'), 'releaseDate': (tags.get('date') or tags.get('date_released') or tags.get('year') or '')[:32] or None}
