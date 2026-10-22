# Converts merged OSM export -> disha/assets/places.csv (cat \t lat \t lon \t name \t kind), 8 km around MIT Manipal
import json,math
def dist(la,lo):
  R=6371000;p1,p2=math.radians(13.3525),math.radians(la);dl=math.radians(lo-74.7926)
  h=math.sin((p2-p1)/2)**2+math.cos(p1)*math.cos(p2)*math.sin(dl/2)**2;return 2*R*math.asin(math.sqrt(h))
REL={'hindu':'temple','christian':'church','muslim':'mosque','jain':'jain basadi','buddhist':'buddhist shrine','sikh':'gurudwara'}
rows=[];cnt=[0,0,0]
for x in json.load(open('merged.json')):
  t=x['tags']; la,lo=x['lat'],x['lon']
  if dist(la,lo)>8000: continue
  s,a=t.get('shop'),t.get('amenity')
  if s in('tobacco','e-cigarette','kiosk'): c,k=0,'tobacco shop'
  elif s in('convenience','general'): c,k=0,'general store'
  elif s in('alcohol','wine') or a in('bar','pub','biergarten'): c,k=1,{'alcohol':'liquor shop','wine':'wine shop'}.get(s,a)
  elif a=='place_of_worship' or 'religion' in t: c,k=2,REL.get(t.get('religion'),'place of worship')
  else: continue
  name=(t.get('name:en') or t.get('name') or '').replace('\t',' ').strip()
  if name=='Wine Gte': name='Wine Gate'
  rows.append((c,la,lo,name,k)); cnt[c]+=1
rows.sort()
with open('disha/assets/places.csv','w') as f:
  f.write('# cat\tlat\tlon\tname\tkind  (0=sutta 1=daaru 2=mandir) source: OpenStreetMap contributors, ODbL\n')
  for r in rows: f.write('%d\t%.6f\t%.6f\t%s\t%s\n'%r)
print(cnt)
