# Wiki 目录说明

本目录是项目文档中心（`docs/`）的 **GitHub Wiki 格式导出**，用于发布到 GitHub 仓库的 Wiki。

## 重要：GitHub Wiki 扁平化约定

GitHub Wiki **不支持子目录**——只有仓库**根目录**下的 `.md` 文件才会被识别为 Wiki 页面，子目录内的文件不会被索引，链接会显示为空白。

因此本目录**所有页面必须放在根目录**，文件命名规则：`原文件名.md`（分类信息保留在 `_Sidebar.md` 导航中）。`docs/` 中的 `.txt` 文档转存为同名 `.md`。

## 与 docs/ 的映射

| docs/ 分类目录 | wiki 页面（根目录平铺） |
|------|------|
| `docs/01-架构设计/` | 架构全盘说明.md、架构设计说明.md、架构评估报告.md、ADR-架构决策记录.md、系统架构说明.md、LLM策略与路由说明.md |
| `docs/02-API与数据库/` | api-design.md、数据库设计说明.md、数据库ER图.md |
| `docs/03-运维部署/` | 部署运维手册.md、故障排查指南.md、CI_CD.md |
| `docs/04-安全合规/` | 安全配置说明.md、安全合规说明.md |
| `docs/05-测试与质量/` | 测试规范.md、压测与优化报告_20260811.md、代码规范与质量说明.md |
| `docs/06-交付材料/` | 项目概述.md、Demo视频或原型内容.md、方案PPT内容.md |
| `docs/07-变更与经验/` | CHANGELOG-3.0.md、MultiAgent升级实录_20260813.md、博思AI智能体3.0最佳实践.md |

## 链接约定

- 页面间链接使用 GitHub Wiki 原生语法 `[[页面名]]`（不加 `.md`，无目录前缀）
- 侧边栏/首页：`_Sidebar.md`、`Home.md`

## 如何更新

```bash
# 1. 重新同步（docs/ 有更新后执行，文件名保持上述映射）
cd wiki
for f in ../docs/01-架构设计/*.md ../docs/02-API与数据库/*.md ../docs/03-运维部署/*.md \
         ../docs/04-安全合规/*.md ../docs/05-测试与质量/*.md ../docs/06-交付材料/*.md \
         ../docs/07-变更与经验/*.md; do cp "$f" "$(basename "$f")"; done
cp ../docs/05-测试与质量/压测与优化报告_20260811.txt 压测与优化报告_20260811.md

# 2. 按需更新 Home.md / _Sidebar.md
```

## 如何发布到 GitHub Wiki

GitHub Wiki 是独立仓库（`<repo>.wiki.git`），直接推送本目录内容即可：

```bash
git clone git@github.com:<owner>/<repo>.wiki.git /tmp/repo-wiki
rsync -a --delete wiki/ /tmp/repo-wiki/
cd /tmp/repo-wiki
git add -A && git commit -m "docs: 同步项目 Wiki" && git push
```

> 注意：Wiki 仓库不带 `README.md` 也可正常工作；`Home.md` 为默认首页。
