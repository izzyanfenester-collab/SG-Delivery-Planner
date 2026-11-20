#!/usr/bin/env python3
"""Inspect permitted OSM/LTA sources before building the road-speed database."""
import collections
import datetime
import gzip
import hashlib
import json
from pathlib import Path
import sys
import urllib.request
import osmium

out=Path(sys.argv[2]);out.mkdir(parents=True,exist_ok=True)
highways={'motorway','motorway_link','trunk','trunk_link','primary','primary_link',
          'secondary','secondary_link','tertiary','tertiary_link','unclassified',
          'residential','living_street','service','road'}
class Roads(osmium.SimpleHandler):
    def __init__(self):
        super().__init__();self.boundary=None;self.count=collections.Counter()
        self.file=gzip.open(out/'ways.jsonl.gz','wt',encoding='utf-8')
    def area(self,a):
        if a.tags.get('ISO3166-1')=='SG' and a.tags.get('admin_level')=='2':
            self.boundary=json.loads(osmium.geom.GeoJSONFactory().create_multipolygon(a))
    def way(self,w):
        t=dict(w.tags)
        if t.get('highway') not in highways:return
        if any(not n.location.valid() for n in w.nodes):return
        points=[[n.lon,n.lat] for n in w.nodes]
        if len(points)<2 or not any(103.54<=x<=104.19 and 1.12<=y<=1.52 for x,y in points):return
        self.count[t['highway']]+=1
        self.file.write(json.dumps({'id':w.id,'tags':t,'nodes':[n.ref for n in w.nodes],
            'points':points,'timestamp':str(w.timestamp),'version':w.version},separators=(',',':'))+'\n')
h=Roads();h.apply_file(sys.argv[1],locations=True,idx='flex_mem');h.file.close()
assert h.boundary is not None,'Singapore administrative boundary missing: do not infer jurisdiction from bbox'
(out/'sg-boundary.json').write_text(json.dumps(h.boundary))
meta={'retrieved_at':datetime.datetime.now(datetime.timezone.utc).isoformat(),
      'osm_pbf_sha256':hashlib.sha256(Path(sys.argv[1]).read_bytes()).hexdigest(),
      'osm_source':'https://download.geofabrik.de/asia/malaysia-singapore-brunei-latest.osm.pbf',
      'bbox_way_counts':dict(h.count)}
try:
    url='https://api-open.data.gov.sg/v1/public/api/datasets/d_bbf0132c7290d6838f82003972d933d5/poll-download'
    req=urllib.request.Request(url,headers={'User-Agent':'SGDelivery-SpeedLimitData/875'})
    with urllib.request.urlopen(req,timeout=90) as r:poll=json.load(r)
    link=poll['data']['url']
    with urllib.request.urlopen(link,timeout=180) as r:raw=r.read(100_000_001)
    assert len(raw)<=100_000_000
    signs=json.loads(raw);features=signs['features']
    meta['lta_signs_sha256']=hashlib.sha256(raw).hexdigest()
    meta['lta_signs_count']=len(features)
    counts=collections.Counter(f.get('properties',{}).get('TYP_CD','') for f in features)
    meta['lta_speed_zone_types']={k:v for k,v in counts.items() if any(s in k.lower() for s in ('speed','school','silver','friendly'))}
    kept=[f for f in features if any(s in f.get('properties',{}).get('TYP_CD','').lower() for s in ('speed','school','silver','friendly'))]
    with gzip.open(out/'lta-speed-signs.json.gz','wt') as f:json.dump({'type':'FeatureCollection','features':kept},f)
except Exception as e:
    meta['lta_download_error']=f'{type(e).__name__}: {e}'
(out/'source-audit.json').write_text(json.dumps(meta,indent=2)+'\n')
print(json.dumps(meta,indent=2))
