# Regenerate ../spark-compat-design.html from the Markdown docs.
# Usage: pip install markdown && python3 build_page.py
import os, re, html, csv, json, markdown
HERE=os.path.dirname(os.path.abspath(__file__))
D=os.path.join(HERE,'..')+'/'
docs=[('README.md','overview','总览'),('01-architecture.md','arch','01 架构'),('02-spark-dialect-parser.md','dialect','02 方言'),
('03-function-adapter.md','func','03 函数适配'),('04-case-sensitivity.md','case','04 大小写'),('05-semantics-session-catalog.md','sem','05 语义'),
('06-testing-strategy.md','test','06 测试'),('07-roadmap-cost-benefit.md','plan','07 路线与成本'),
('handover/README.md','ho','接管手册索引'),('handover/dev-workflow.md','ho-dev','开发工作流'),('handover/fe-query-pipeline.md','ho-feq','FE 查询链路'),
('handover/fe-metadata-platform.md','ho-fem','FE 元数据与平台'),('handover/be-execution-functions.md','ho-be','BE 执行与函数'),('handover/implementation-map.md','ho-map','任务单 (实施地图)')]
import posixpath
idmap={d[0]:d[1] for d in docs}
def resolve(base,href):
    path=href.split('#')[0]
    if not path: return None
    full=posixpath.normpath(posixpath.join(posixpath.dirname(base),path))
    return full
sections=[]
for fn,aid,label in docs:
    s=open(D+fn).read()
    blocks=[]
    def keep(m):
        blocks.append(m.group(1)); return f'\n\nMERMAIDBLOCK{len(blocks)-1}\n\n'
    s=re.sub(r'```mermaid\n(.*?)```',keep,s,flags=re.S)
    def link(m):
        text,href=m.group(1),m.group(2)
        if href.startswith('http') or href.startswith('#'): return m.group(0)
        full=resolve(fn,href)
        if full in idmap: return f'[{text}](#{idmap[full]})'
        if full=='func_mapping.csv': return f'[{text}](#fnmap)'
        gh='https://github.com/Egeek2025/starrocks/blob/docs/spark-sql-compat-design/handbook/plans/active/2026-11-05-spark-sql-compat/'
        target=posixpath.normpath(posixpath.join('handbook/plans/active/2026-11-05-spark-sql-compat',full))
        return f'[{text}](https://github.com/Egeek2025/starrocks/blob/docs/spark-sql-compat-design/{target})'
    s=re.sub(r'\[([^\]]+)\]\(([^)\s]+)\)',link,s)
    h=markdown.markdown(s,extensions=['tables','fenced_code','sane_lists'])
    for i,b in enumerate(blocks):
        h=h.replace(f'<p>MERMAIDBLOCK{i}</p>',f'<div class="diagram"><pre class="mermaid">{html.escape(b)}</pre></div>')
    h=re.sub(r'<table>',r'<div class="tbl"><table>',h); h=h.replace('</table>','</table></div>')
    h=h.replace('<pre><code','<div class="code"><pre><code').replace('</code></pre>','</code></pre></div>')
    sections.append((aid,label,h))
rows=list(csv.DictReader(open(D+'func_mapping.csv')))
navd=''.join(f'<a href="#{a}">{l}</a>' for a,l,_ in sections if not a.startswith('ho'))
navh=''.join(f'<a href="#{a}">{l}</a>' for a,l,_ in sections if a.startswith('ho'))
nav='<div class="grp">设计</div>'+navd+'<a href="#fnmap">函数映射表</a><div class="grp">接管手册</div>'+navh
body=''.join(f'<section id="{a}" class="doc">{h}</section>' for a,l,h in sections)
tpl=open(os.path.join(HERE,'page_tpl.html')).read()
out=tpl.replace('%%NAV%%',nav).replace('%%BODY%%',body).replace('%%ROWS%%',json.dumps([[r['spark_name'],r['category'],r['class'],r['starrocks_equiv'],r['difference'],r['strategy'],r['difficulty']] for r in rows],ensure_ascii=False).replace('</','<\\/'))
open(D+'spark-compat-design.html','w').write(out)
print(len(out))
