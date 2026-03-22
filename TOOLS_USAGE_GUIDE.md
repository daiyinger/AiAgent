# Read/Edit 工具配合使用指南

本指南说明如何正确使用 `read_file` 和 `edit_file` 工具的配合功能，以及新增的安全特性。

---

## 📋 概述

本次优化主要解决以下问题：
1. **行号不一致风险** - 多轮编辑后行号可能失效
2. **缺少编辑上下文验证** - 无法检测文件是否被外部修改
3. **工具返回信息不足** - 缺少结构化数据和指导信息

---

## 🔧 新增功能

### 1. 文件校验和 (Checksum)

**目的**: 检测文件在读取和编辑之间是否被修改

**使用流程**:
```
1. read_file 返回 checksum
2. edit_file 接收 checksum 参数验证
3. 如果校验和不匹配，编辑会失败并提示重新读取
```

**示例**:
```json
// read_file 返回
{
  "path": "src/Main.kt",
  "content": "10. fun calculate() { ... }",
  "checksum": "a1b2c3d4e5f6..."  // SHA-256 校验和
}

// edit_file 使用
{
  "path": "src/Main.kt",
  "start_line": 10,
  "end_line": 12,
  "new_text": "fun calculate(): Int { ... }",
  "checksum": "a1b2c3d4e5f6..."  // 从 read_file 获取
}
```

---

### 2. 预期旧文本验证 (Expected Old Text)

**目的**: 确保编辑位置的内容与预期一致

**使用流程**:
```
1. read_file 获取目标行内容
2. edit_file 使用 expected_old_text 参数
3. 如果实际内容不匹配，编辑会失败
```

**示例**:
```json
// read_file 返回
{
  "content": "10. val config = Config()",
  "lines": [{"number": 10, "content": "val config = Config()"}]
}

// edit_file 使用
{
  "path": "src/Main.kt",
  "start_line": 10,
  "end_line": 10,
  "new_text": "val settings = Settings()",
  "expected_old_text": "val config = Config()"  // 验证内容
}
```

---

### 3. 结构化行数据

**目的**: 提供更精确的行信息，便于 AI 精确编辑

**read_file 新增返回字段**:
```json
{
  "lines": [
    {
      "number": 10,
      "content": "fun calculate() {",
      "indent": 4  // 缩进空格数
    },
    {
      "number": 11,
      "content": "    val x = 5",
      "indent": 8
    }
  ],
  "fileInfo": {
    "totalLines": 100,
    "readRange": "10-15",
    "encoding": "UTF-8"
  }
}
```

---

### 4. 编辑预览工具 (Preview Edit)

**目的**: 在执行编辑前查看将要进行的修改

**使用流程**:
```
1. read_file 读取文件
2. preview_edit 预览修改效果
3. 确认无误后使用 edit_file 执行
```

**示例**:
```json
// preview_edit 请求
{
  "path": "src/Main.kt",
  "start_line": 10,
  "end_line": 12,
  "new_text": "fun calculate(): Int {\n    return 42\n}",
  "context_lines": 3
}

// preview_edit 返回
{
  "can_apply": true,
  "diff": "   8| class Main {\n   9| \n- 10| fun calculate() {\n- 11|     val x = 5\n- 12| }\n+ 10| fun calculate(): Int {\n+ 11|     return 42\n+ 12| }\n  13| \n  14| fun main() {",
  "summary": "Will replace lines 10-12 (no line change). File will have 100 lines.",
  "line_change": 0,
  "total_lines_after": 100
}
```

---

## ✅ 最佳实践

### 正确的读取-编辑循环

```
步骤 1: 读取文件
┌─────────────────────────────────┐
│ read_file(                      │
│   path="src/Main.kt",           │
│   start_line=10,                │
│   end_line=20                   │
│ )                               │
│ 返回: checksum, lines, content  │
└─────────────────────────────────┘
           │
           ▼
步骤 2: 预览编辑（可选但推荐）
┌─────────────────────────────────┐
│ preview_edit(                   │
│   path="src/Main.kt",           │
│   start_line=10,                │
│   end_line=12,                  │
│   new_text="new code"           │
│ )                               │
│ 返回: diff, can_apply           │
└─────────────────────────────────┘
           │
           ▼
步骤 3: 执行编辑
┌─────────────────────────────────┐
│ edit_file(                      │
│   path="src/Main.kt",           │
│   start_line=10,                │
│   end_line=12,                  │
│   new_text="new code",          │
│   checksum="a1b2c3...",         │ ← 从步骤1获取
│   expected_old_text="old code"  │ ← 从步骤1获取
│ )                               │
│ 返回: new_end_line, total_lines │
└─────────────────────────────────┘
           │
           ▼
步骤 4: 继续编辑（如果需要）
┌─────────────────────────────────┐
│ 使用步骤3返回的:                │
│ - new_end_line 作为参考         │
│ - total_lines 计算新行号        │
│                                 │
│ 重新读取已修改的区域            │
│ 获取新的 checksum               │
└─────────────────────────────────┘
```

---

### 处理行号变化

**问题**: 编辑后行号会发生变化

**解决方案**:
```json
// 第一次编辑
edit_file({
  "start_line": 10,
  "end_line": 10,  // 替换1行
  "new_text": "line1\nline2\nline3"  // 插入3行
})
// 返回: { "new_end_line": 12, "line_change": 2, "total_lines": 102 }

// 如果要在新插入的内容后继续编辑
// 新行13 = 原行11 (10 + 3行新内容 - 1 + 1)
// 或者重新读取:
read_file({
  "start_line": 13,  // 从新内容之后开始
  "end_line": 20
})
```

---

### 处理校验和不匹配

**场景**: 文件在读取和编辑之间被外部修改

```
❌ 错误流程:
read_file → 用户修改文件 → edit_file (使用旧的 checksum)
结果: "File checksum mismatch!"

✅ 正确流程:
read_file → 用户修改文件 → edit_file 失败 → 重新 read_file → edit_file
```

---

## 🛡️ 安全特性

### 1. 路径安全
- 自动规范化路径
- 防止路径穿越攻击 (`../../../etc/passwd`)
- 只能操作项目目录内的文件

### 2. 校验和验证
- SHA-256 算法
- 检测文件并发修改
- 防止静默数据覆盖

### 3. 内容验证
- 预期旧文本匹配
- 行号范围检查
- 防止意外修改

---

## 📊 返回值说明

### read_file 返回

| 字段 | 类型 | 说明 |
|------|------|------|
| `path` | string | 文件路径 |
| `content` | string | 带行号的内容（兼容格式） |
| `lines` | array | 结构化行数据 |
| `checksum` | string | SHA-256 校验和 |
| `lineCount` | int | 文件总行数 |
| `actualStartLine` | int | 实际起始行 |
| `actualEndLine` | int | 实际结束行 |
| `fileInfo` | object | 文件元信息 |

### edit_file 返回

| 字段 | 类型 | 说明 |
|------|------|------|
| `success` | boolean | 是否成功 |
| `message` | string | 操作描述 |
| `old_text` | string | 被替换的原始文本 |
| `new_text` | string | 新插入的文本 |
| `start_line` | int | 实际起始行 |
| `end_line` | int | 实际结束行 |
| `new_end_line` | int | 新内容的结束行 |
| `total_lines` | int | 编辑后文件总行数 |
| `line_change` | int | 行数变化（+增加/-减少） |

### preview_edit 返回

| 字段 | 类型 | 说明 |
|------|------|------|
| `can_apply` | boolean | 是否可以安全应用 |
| `diff` | string | diff 格式的预览 |
| `summary` | string | 操作摘要 |
| `line_change` | int | 行数变化 |
| `total_lines_after` | int | 修改后总行数 |

---

## 🔍 常见问题

### Q1: 为什么需要校验和？
**A**: 防止文件在读取和编辑之间被修改。如果没有校验和，可能会覆盖其他人的修改。

### Q2: 什么时候应该使用 preview_edit？
**A**: 在进行复杂编辑或多处修改前，预览可以帮助确认修改是否正确。

### Q3: 如何处理大文件？
**A**: 使用 `start_line` 和 `max_lines` 分段读取，避免一次性加载整个文件。

### Q4: 编辑失败后怎么办？
**A**: 
1. 重新读取文件获取最新内容和校验和
2. 如果需要预览，使用 preview_edit
3. 使用新的校验和和行号重新编辑

### Q5: 结构化数据有什么用？
**A**: 
- `indent` 字段帮助保持代码缩进一致
- `lines` 数组便于精确定位特定行
- 避免手动解析行号前缀

---

## 💡 示例场景

### 场景 1: 安全地修改函数

```json
// 1. 读取函数所在行
read_file({
  "path": "src/Calculator.kt",
  "start_line": 15,
  "end_line": 25
})

// 返回:
{
  "content": "15. fun add(a: Int, b: Int): Int {\n16.     return a + b\n17. }",
  "checksum": "abc123...",
  "lines": [
    {"number": 15, "content": "fun add(a: Int, b: Int): Int {", "indent": 0},
    {"number": 16, "content": "    return a + b", "indent": 4},
    {"number": 17, "content": "}", "indent": 0}
  ]
}

// 2. 预览修改
preview_edit({
  "path": "src/Calculator.kt",
  "start_line": 15,
  "end_line": 17,
  "new_text": "fun add(a: Int, b: Int): Long {\n    return (a + b).toLong()\n}"
})

// 3. 执行修改
edit_file({
  "path": "src/Calculator.kt",
  "start_line": 15,
  "end_line": 17,
  "new_text": "fun add(a: Int, b: Int): Long {\n    return (a + b).toLong()\n}",
  "checksum": "abc123...",
  "expected_old_text": "fun add(a: Int, b: Int): Int {\n    return a + b\n}"
})
```

### 场景 2: 在多处修改后继续编辑

```json
// 第一次编辑（在第10行插入2行）
edit_file({
  "start_line": 10,
  "end_line": 10,
  "new_text": "import java.util\nimport java.io"
})
// 返回: { "new_end_line": 11, "total_lines": 102 }

// 如果要在第20行继续编辑
// 原第20行现在是第22行 (20 + 2)
// 或者重新读取:
read_file({
  "start_line": 22,  // 使用新的行号
  "end_line": 30
})
```

---

## 📝 总结

通过这些优化，read 和 edit 工具的配合更加安全和可靠：

1. ✅ **校验和保护** - 防止文件并发修改
2. ✅ **内容验证** - 确保编辑位置正确
3. ✅ **预览功能** - 提前确认修改效果
4. ✅ **结构化数据** - 便于精确定位和编辑
5. ✅ **行号指导** - 帮助计算后续编辑的行号

遵循最佳实践，可以大大减少编辑错误和数据丢失的风险。

---

*文档版本: 1.0*
*更新时间: 2026-03-22*