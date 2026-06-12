# Wiki source

This folder is the source of truth for the project documentation. The pages are written as standard Markdown with relative `.md` links, so they render and link correctly **both** when browsed here in the code repo **and** when published to the GitHub Wiki tab.

## Pages

| File | Page |
|------|------|
| `Home.md` | Landing page / index |
| `Architecture.md` | System layers, request flow, design decisions |
| `Read-Only-Security-Guardrails.md` | The 4-layer read-only enforcement |
| `Pricing-Architecture.md` | Cost calculation, regional rates, caching |
| `Resource-Scanners.md` | All 50 scanners and cost logic |
| `Configuration-Reference.md` | Every tunable property / env var |
| `API-Reference.md` | REST + actuator endpoints |
| `Troubleshooting.md` | Common startup and runtime issues |
| `FAQ.md` | Frequently asked questions |
| `_Sidebar.md` | Wiki tab left-nav (GitHub renders this automatically) |
| `_Footer.md` | Wiki tab footer (GitHub renders this automatically) |

## Publishing to the GitHub Wiki tab

The Wiki is a separate git repository (`<repo>.wiki.git`). To publish:

```bash
git clone https://github.com/techexorcist/cloud-resource-sentinel.wiki.git
cd cloud-resource-sentinel.wiki
cp ../cloud-resource-sentinel/wiki/*.md .
git add -A
git commit -m "Update wiki"
git push origin master   # wiki default branch is 'master'
```

`_Sidebar.md` and `_Footer.md` are special filenames GitHub renders on every wiki page automatically.
