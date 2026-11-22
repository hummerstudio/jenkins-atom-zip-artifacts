# Merged into `atom-basic-steps`

通用、与任何外部产品无关的文件动作应该像 Jenkins 的 Pipeline Basic Steps 一样集中在一个原子里，
而不是一个动作一个仓库。

```groovy
// 旧
runAtom(atom: 'zip-artifacts', version: '1.latest', inputs: [...])
// 新
runAtom(atom: 'atom-basic-steps', version: '1.1.0', inputs: [action: 'zip', ...])
```

输入名与错误码保持不变（`archive` / `includes` / `excludes` / `target` / `baseDir` / `patterns` / `outputFile` / `deleteSource`）。
本仓库只读保留，便于历史构建追溯。
