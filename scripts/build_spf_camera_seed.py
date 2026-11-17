#!/usr/bin/env python3
"""B865: reproduce a strictly validated, offline SPF traffic-enforcement snapshot.
Never infer a camera's bearing, current activation, or posted road speed limit.
"""
import argparse
import datetime as dt
import hashlib
import json
import pathlib
import re
import urllib.request
from bs4 import BeautifulSoup

SPEED_URL="https://www.police.gov.sg/Knowledge-Hub/Traffic/Traffic-Matters/Speed-Enforcement-Camera-Locations"
RED_URL="https://www.police.gov.sg/knowledge-hub/traffic/traffic-matters/red-light-camera-locations"
LTA_URL="https://www.lta.gov.sg/content/ltagov/en/getting_around/driving_in_singapore/driving_rules_and_regulations.html"
COORD=re.compile(r'^\s*(1\.[0-9]+)\s*,\s*(10[34]\.[0-9]+)\s*$')
DIRECTION=re.compile(r'\s+(?:towards|twds|->)\s+',re.IGNORECASE)
JUNCTION=re.compile(r'\s+by\s+',re.IGNORECASE)
DATES={'RED_LIGHT_CAMERA':'2026-07-01','FIXED_SPEED_CAMERA':None,'LTA_SPEED_CAMERA':'2025-07-07',
       'MOBILE_ENFORCEMENT_LOCATION':'2026-07-01','AVERAGE_SPEED_ZONE':'2025-07-07',
       'POLICE_LASER_LOCATION':'2026-07-29'}

def load(url):
    request=urllib.request.Request(url,headers={
        'User-Agent':'Mozilla/5.0 (compatible; SGDeliveryTrafficSafety/1.0; public SPF data)',
        'Accept':'text/html'})
    with urllib.request.urlopen(request,timeout=40) as response:
        payload=response.read(2_000_000)
        if not payload or len(payload)>=2_000_000: raise ValueError('SPF page response too large or empty')
    soup=BeautifulSoup(payload,'html.parser')
    if not soup.title or 'SPF |' not in soup.title.get_text(' ',strip=True):
        raise ValueError('Unexpected SPF page title')
    return soup

def read_rows(table):
    return [[cell.get_text(' ',strip=True) for cell in tr.find_all(['td','th'])]
            for tr in table.find_all('tr')]

def parse_coord(value):
    match=COORD.fullmatch(value)
    if not match: raise ValueError('Invalid SPF coordinate: '+repr(value))
    lat,lon=map(float,match.groups())
    if not (1.1<lat<1.5 and 103.5<lon<104.2):
        raise ValueError('Coordinates outside Singapore camera area')
    return lat,lon

def item(category,number,name,coord,url,suffix='',zone=None):
    lat,lon=parse_coord(coord)
    direction=DIRECTION.split(name,maxsplit=1)
    road=JUNCTION.split(direction[0],maxsplit=1)
    return dict(camera_id=f'{category}-{number:03d}{suffix}',official_name=name,
        category=category,latitude=lat,longitude=lon,road_name=road[0].strip(),
        direction_text=direction[1].strip() if len(direction)>1 else None,
        junction_name=road[1].strip() if len(road)>1 else None,
        road_segment_id=None,direction_bearing=None, direction_verified=False,
        source_url=url,source_publication_date=DATES[category],
        speed_enforcement_status=('POSSIBLE_UNVERIFIED' if category=='RED_LIGHT_CAMERA'
            else 'PUBLISHED_LOCATION_NOT_LIVE_STATUS'),
        speed_limit_kmh=None,speed_limit_source=None,speed_limit_verified_at=None,
        average_speed_zone_id=zone,average_zone_entry_verified=False,
        average_zone_exit_verified=False)

def parse_standard(table,category,url):
    output=[]
    for row in read_rows(table):
        if not row or not row[0].isdigit(): continue
        if len(row)!=3: raise ValueError(f'Unexpected {category} row: {row}')
        output.append(item(category,int(row[0]),row[1],row[2],url))
    return output

def parse_lta(table):
    output=[];pending=None
    for row in read_rows(table):
        if not row:continue
        if len(row)==2 and all(COORD.fullmatch(x) for x in row):
            if pending is None: raise ValueError('Unexpected MCE camera pair without owner row')
            no,loc=pending
            output.extend([item('LTA_SPEED_CAMERA',no,loc,point,SPEED_URL,suffix='-'+suffix)
                           for suffix,point in zip(('A','B'),row)])
            pending=None
        elif row[0].isdigit():
            if pending: raise ValueError('Missing MCE camera pair')
            no=int(row[0]);loc=row[1]
            if len(row)==3 and COORD.fullmatch(row[2]):
                output.append(item('LTA_SPEED_CAMERA',no,loc,row[2],SPEED_URL))
            elif len(row)==4 and row[2:] == ['Camera A','Camera B']:
                pending=(no,loc)
            else: raise ValueError('Unexpected LTA SPF row '+repr(row))
    if pending: raise ValueError('Missing MCE camera coordinates')
    return output

def parse_average(table):
    output=[];zone_name=None;zone_index=0
    for row in read_rows(table):
        if not row or row[0]=='No.':continue
        if len(row)==3 and not row[0].isdigit():
            zone_name=row[0];zone_index+=1
            no=int(row[1]);point=row[2]
        elif len(row)==2 and row[0].isdigit() and zone_name:
            no=int(row[0]);point=row[1]
        else:raise ValueError('Unexpected average-speed section row '+repr(row))
        zone_id=f'SPF-TANAH-MERAH-{zone_index}'
        output.append(item('AVERAGE_SPEED_ZONE',no,zone_name,point,SPEED_URL,
            suffix=f'-D{zone_index}',zone=zone_id))
    return output

def build():
    red_tables=load(RED_URL).find_all('table')
    speed_tables=load(SPEED_URL).find_all('table')
    if len(red_tables)!=1 or len(speed_tables)!=5:
        raise ValueError(f'SPF page format changed: red={len(red_tables)}, speed={len(speed_tables)}')
    rows=[]
    rows+=parse_standard(red_tables[0],'RED_LIGHT_CAMERA',RED_URL)
    rows+=parse_standard(speed_tables[0],'FIXED_SPEED_CAMERA',SPEED_URL)
    rows+=parse_lta(speed_tables[1])
    rows+=parse_standard(speed_tables[2],'MOBILE_ENFORCEMENT_LOCATION',SPEED_URL)
    rows+=parse_average(speed_tables[3])
    rows+=parse_standard(speed_tables[4],'POLICE_LASER_LOCATION',SPEED_URL)
    by_category={category:sum(r['category']==category for r in rows)
                 for category in DATES}
    expected=dict(RED_LIGHT_CAMERA=241,FIXED_SPEED_CAMERA=20,LTA_SPEED_CAMERA=13,
                  MOBILE_ENFORCEMENT_LOCATION=5,AVERAGE_SPEED_ZONE=12,POLICE_LASER_LOCATION=50)
    if by_category!=expected:
        raise ValueError('SPF category counts unexpectedly changed; manual review required: '+
                         repr(by_category))
    ids=[r['camera_id'] for r in rows]
    if len(set(ids))!=len(ids): raise ValueError('Duplicate camera IDs')
    digest=hashlib.sha256(json.dumps(rows,sort_keys=True,separators=(',',':')).encode()).hexdigest()
    now=dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat().replace('+00:00','Z')
    snapshot=dict(schema_version=1,database_version='spf-'+digest[:16],
        last_successful_refresh=now,
        sources=dict(red_light=RED_URL,speed=SPEED_URL,road_speed_rules=LTA_URL),
        published_record_counts=by_category,record_count=len(rows),
        current_activation_claimed=False,road_limits_verified=0,cameras=rows)
    assert snapshot['record_count']==341
    return snapshot

if __name__=='__main__':
    arg=argparse.ArgumentParser()
    arg.add_argument('--output',default='app/src/main/assets/sg_enforcement_cameras.json')
    args=arg.parse_args()
    out=pathlib.Path(args.output)
    data=build()
    out.parent.mkdir(parents=True,exist_ok=True)
    out.write_text(json.dumps(data,ensure_ascii=False,separators=(',',':'))+'\n',encoding='utf-8')
    print('B865 validated',data['record_count'],'official SPF camera rows; by type:',data['published_record_counts'])
    print('SPF snapshot',data['database_version'],'refreshed',data['last_successful_refresh'])
    print('No numeric speed limits or unverified direction bearings assigned.')
