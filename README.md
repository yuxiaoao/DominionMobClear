# DominionMobClear

Dominion 附属插件：定时检查领地内是否有敌对生物，有就清理。

## 功能

- 每隔 N tick 扫描已加载区块，发现领地内的 `Enemy` 就删除
- **按领地单独开关**：Dominion flag `hostile_mob_clear_claim`（默认关闭）
- 领地主人站在领地里执行 `/dmc toggle` 即可切换；也可以在 Dominion 的 flag 设置里改
- 可配置清理方式：`remove`（无掉落）或 `kill`（有掉落）
- 可忽略命名怪、驯服生物、Boss、指定实体类型
- 可黑名单世界
- 每轮有实体检查上限，避免单次扫描过久

## 构建

需要本地有 DominionAPI：

```bash
mvn install:install-file -Dfile=Dominion-4.9.6-release-full.jar \
  -DgroupId=cn.lunadeer -DartifactId=DominionAPI -Dversion=4.9.6 -Dpackaging=jar
mvn package
```

产物：`target/DominionMobClear-1.4.2.jar`

## 安装

1. 把 jar 放进 `plugins/`
2. 确保 Dominion 已安装
3. 重启服务器
4. 首次启动生成 `plugins/DominionMobClear/config.yml`

## 领地开关

只有一种开关，默认关闭：

- 每块领地的 Dominion flag：`hostile_mob_clear_claim`
- 领地主人站在该领地里执行 `/dmc toggle` 切换
- 或者在 Dominion 的 flag 设置里单独开启/关闭这块领地

玩家 1 开启自己的领地，只影响玩家 1 的领地；玩家 2 不开，玩家 2 的领地不会清理。

## 命令

```text
/dmc here            查看你当前站立的领地及清理开关（别人的领地也能看）
/dmc status [领地]   查看自己当前/指定领地是否开启清理
/dmc toggle [领地]   切换自己当前/指定领地的清理
/dmc list            列出自己的领地及开关状态
/dmc clear           立即扫描一次（管理员）
/dmc reload          重载配置（管理员）
```

说明：

- 普通玩家只能查看/切换自己是主人的领地
- 管理员也只能 `list` 和 `status` 自己的领地
- 管理员可以手动输入别人领地的名字执行 `/dmc toggle <领地名>` 帮别人开关
- 领地名 Tab 补全只列出**自己的**领地，不会列出别人的领地
- 普通玩家使用 `toggle` / `status` / `list` 管理自己的领地**不需要任何权限**
- `dominionmobclear.admin`：`clear` / `reload`，以及查看、切换别人的领地

## 配置

```yaml
enabled: true
interval-ticks: 100         # 5 秒
max-checks-per-sweep: 3000
max-indexed-chunks: 50000    # 有领地的区块索引上限，超过则回退全量扫描
index-refresh-seconds: 60    # 定时重建领地区块索引，兜底异步创建
player-radius-chunks: 8      # 只扫在线玩家附近的领地区块，0 = 不限制
clear-mode: remove          # remove / kill
use-dominion-flag: true
ignore-worldwide: true      # 忽略 Dominion 的世界级领地（id -1）
ignore:
  named: true
  tamed: true
  bosses: true
  types: []
worlds:
  blacklist: []
```

## 性能

- 只扫描**有领地覆盖且已加载**的区块，不再遍历所有已加载区块
- 索引只收**开启了清理 flag** 的领地（flag 默认关闭，索引量很小）
- 领地创建/删除/改大小时自动重建区块索引
- 索引超过 `max-indexed-chunks` 时回退为扫描全部已加载区块（会明显变慢，日志里有警告）
- 只扫描**在线玩家 `player-radius-chunks` 半径内**的领地区块；敌对生物只在玩家附近刷新，远处领地不用扫
- 只对 `Enemy` 查领地
- 每轮最多检查 `max-checks-per-sweep` 个敌对生物
- 单次扫描一般 < 1ms
- 行为说明：玩家走远后领地里的残留敌对生物不会立刻清掉，等玩家走近（进入半径）后的下一轮扫描才会清理
