#!/usr/bin/env python3
import re
import urllib.request
from bs4 import BeautifulSoup

SOURCES = {
    'red': 'https://www.police.gov.sg/knowledge-hub/traffic/traffic-matters/red-light-camera-locations',
    'speed': 'https://www.police.gov.sg/Knowledge-Hub/Traffic/Traffic-Matters/Speed-Enforcement-Camera-Locations'
}
for key, url in SOURCES.items():
    req=urllib.request.Request(url,headers={'User-Agent':'Mozilla/5.0 (compatible; SGDeliveryCameraDataVerification/1.0)','Accept':'text/html'})
    with urllib.request.urlopen(req,timeout=35) as r:
        html=r.read().decode('utf-8','replace')
    soup=BeautifulSoup(html,'html.parser')
    tables=soup.select('table')
    print('SOURCE',key,'html_bytes',len(html),'table_count',len(tables),'title',soup.title.get_text(' ',strip=True) if soup.title else 'none')
    for i,table in enumerate(tables):
        rows=table.find_all('tr')
        sample=[[c.get_text(' ',strip=True) for c in row.find_all(['th','td'])] for row in rows[:3]]
        nearby=table.find_previous(['h2','h3','h4','h5','h6'])
        print('TABLE',i,'count',len(rows),'heading',nearby.get_text(' ',strip=True) if nearby else None,'sample',repr(sample)[:550])
    print('TEXT_CAMERA',re.findall(r'.{0,60}Camera Locations.{0,60}',soup.get_text(' ',strip=True),flags=re.I)[:10])

    if key=='speed':
        for idx in (1,3):
            table=tables[idx]
            for row in table.find_all('tr'):
                print('SPECIAL_TABLE_ROW',idx,repr([c.get_text(' ',strip=True) for c in row.find_all(['th','td'])]))
