#!/usr/bin/env python3
"""Inspect public SPF enforcement tables. Fail closed on changed/missing layouts."""
import requests
from bs4 import BeautifulSoup
SOURCES = {
  "red": "https://www.police.gov.sg/knowledge-hub/traffic/traffic-matters/red-light-camera-locations",
  "speed": "https://www.police.gov.sg/Knowledge-Hub/Traffic/Traffic-Matters/Speed-Enforcement-Camera-Locations"
}
for kind, url in SOURCES.items():
    r = requests.get(url, headers={"User-Agent":"SGDeliveryCameraSafety/0.1 (+manual-source-verification; weekly)"}, timeout=35)
    print("HTTP",kind,r.status_code,len(r.content),r.url,flush=True);r.raise_for_status()
    soup = BeautifulSoup(r.text,"lxml")
    for tag in soup(["script","style","nav","footer"]): tag.decompose()
    tabs=soup.select("table")
    print("TABLE COUNT",kind,len(tabs),flush=True)
    for i,table in enumerate(tabs):
        rows=table.select("tr")
        ancestor=table.find_previous(["h1","h2","h3","h4","h5","h6"])
        head=ancestor.get_text(" ",strip=True) if ancestor else ""
        print("TABLE",i,"HEADING",repr(head[:120]),"ROWS",len(rows),"FIRST",[r.get_text(" | ",strip=True)[:200] for r in rows[:3]],flush=True)
    titles=soup.find_all(["h1","h2","h3","h4","h5","h6"])
    print("HEADINGS",kind,[h.get_text(" ",strip=True) for h in titles[-35:]],flush=True)
