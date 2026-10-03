# EVOPS 基础工作区

这是 springboot的骨架项目。

技术栈：Java 8、Spring Boot 2.7、MyBatis-Plus、H2 本地文件数据库、Shiro、Thymeleaf。

启动前准备：

1. 执行 `mvn -q -DskipTests compile` 验证骨架构建。
2. 执行 `mvn spring-boot:run` 启动应用；Spring Boot 会自动执行 `src/main/resources/schema.sql`。
3. H2 数据文件默认写入工作区 `data/evops`。如需修改路径，编辑 `application.yml` 中的 `jdbc:h2:file:` 连接串。

> 交付副本约定路径为根目录 `..\..\docs\schema\evops.sql`（即 `/docs/schema/evops.sql`）。
> 当前环境根目录不可写，副本放在工作区内 `docs/schema/evops.sql`，与 `src/main/resources/schema.sql` 保持一致。

## 考试机构考场与准考证建档工作区（exam-1）

覆盖四步：**报名 → 编排 → 发证 → 查档**。所有接口在 Shiro 保护下，使用 HTTP Basic 认证：
`curl -u bootstrap:bootstrap ...`；经办人通过请求头 `X-Operator` 记录（缺省 `system`）。

> cert-1 起新增**用人单位专用账号** `employer/employer`，只能访问对外验真窗口 `/api/cert-verify`；
> 用它访问其余 `/api/**` 返回 403。内部管理仍用 `bootstrap/bootstrap`（角色 `system`）。

约定：
- 所有响应统一为 `{success, code, message, data}`；失败返回可读状态码与中文原因（含 401/404），不返回空白。
- **批量动作分两步**：先调 `*/preview` 看影响范围（可处理/跳过逐条列出），确认后再调执行接口。
- **阶段结果**（`t_stage_result`）与**最终结论**（`t_final_conclusion`）分表保存。
- **版本可追溯**：快照只追加；版本选择只切当前标记；回退按快照还原并生成新的 ROLLBACK 版本，原版本与事件链（CREATE/SELECT/ROLLBACK）都保留。

### 1. 建档：考点 / 考场 / 批次

- `POST /api/sites`、`GET /api/sites`：考点（编码、名称、位置、联系人）。
- `POST /api/rooms`、`GET /api/rooms?siteId=`：考场（考场号、楼栋、楼层、**容量**、位置描述）。
- `POST /api/batches`、`GET /api/batches`：考试批次（归属考点、科目、考试时间）。

### 2. 报名（名册挂进批次，支持勘误留痕）

- `POST /api/registrations/import/preview` → `POST /api/registrations/import`：批量导入，报名号撞号**整批拒收**。
- `POST /api/registrations/{id}/corrections`：勘误。旧值写入 `t_registration_correction`，不抹历史；主表记新值并累加更正次数。
- `GET /api/registrations/{id}/corrections`：查更正痕迹。
- `GET /api/registrations/search/by-name?name=`：按姓名查名册（包含匹配，重名全返回）。
- `GET /api/registrations?batchId=`：批次名册。

### 3. 编排（按批次生成准考证，一人一条）

- `POST /api/arrange/preview` → `POST /api/arrange`：
  按**同校混编算法**排座（同校按容量比例摊到多个考场、考场内交错入座），证号规则 `ZK + 批次编码 + 4 位流水`。
  证号唯一、批次内（考场+座位）唯一，**撞号直接拒收**；容量不足整批拒收、不落半条；已编排批次不允许重复编排（要改走整批重排）。
- 报名导入可带 `school`（所属学校）；未填学校按"未知"处理，不参与同校相邻判定。学校字段可勘误（fieldName=`SCHOOL`）。

### 4. 发证（打证、发放两步，各留经办与钟点）

- 打证：`POST /api/tickets/print/preview` → `POST /api/tickets/print`（PENDING→PRINTED，记 printed_by/printed_at）。
- 发放：`POST /api/tickets/issue/preview` → `POST /api/tickets/issue`（PRINTED→ISSUED，记 issued_by/issued_at）。
- 目标可用 `{"batchId":...}` 整批，或 `{"ticketNos":[...]}` 指定；状态不能跳步，重复操作逐条跳过并给原因。
- 证书（证面）打印不在本版范围，下版再做。

### 5. 查档（证号、考场、座位一人一条）

- `GET /api/query/by-ticket-no?ticketNo=`：按证号精确查。
- `GET /api/query/by-name?name=`：按姓名查（含重名）。
- `GET /api/query/by-batch?batchId=`：按批次列全部。
- 注意：查询参数含中文时需做 URL 编码（`curl -G --data-urlencode "name=张三"`）。

### 6. 阶段结果 / 最终结论

- `GET  /api/batches/{id}/progress/stages`：各阶段结果。
- `POST /api/batches/{id}/progress/conclusion`、`GET .../conclusion`：生成 / 查看最终结论（每批次一条）。
- `GET  /api/batches/{id}/progress/overview`：阶段结果与最终结论作为两个字段分开返回。

### 7. 工作区版本（版本选择 / 状态回退可追溯）

- `POST /api/workspace/versions?label=&remark=`：创建快照（自动成为当前版本）。
- `GET  /api/workspace/versions`、`GET /api/workspace/versions/current`、`GET /api/workspace/versions/{no}`。
- `POST /api/workspace/versions/{no}/select`：版本选择（只切当前标记，业务数据不变）。
- `POST /api/workspace/versions/{no}/rollback?remark=`：按该版本快照还原，生成新的 ROLLBACK 版本，原版本保留。
- `GET  /api/workspace/versions/events?versionNo=`：CREATE/SELECT/ROLLBACK 事件链。

## 出分等级与成绩复核工作区（grade-1）

把"出分定级"和"成绩复核"两段在 evops-base 串成一条线：**卷面分按分数段落等级、规则带版本 → 窗口跟批次配置 → 窗口期提复核 → 结论回写通知、改分留痕**。两个岗共用同一套规则版本与窗口口径，不再打架。本批次先跑助考项目（`projectCode=ZHUKAO`）。

### 8. 批次项目与复核窗口（不写死）

- 建批次 `POST /api/batches` 可带 `projectCode`（缺省 `DEFAULT`）与 `reviewStartAt` / `reviewEndAt`。
- `POST /api/batches/{id}/review-window`：单独配置 / 调整窗口（起止必须成对、起早于止）。
- `GET  /api/batches/{id}/review-window`：窗口起止与当前状态 `NOT_CONFIGURED / NOT_OPENED / OPEN / CLOSED`，附各状态复核计数。

### 9. 等级规则模块（规则 + 版本 + 状态）

- `POST /api/grade-rules`、`GET /api/grade-rules?projectCode=`：规则按项目建档（可带 `subject` 按科目细分，留空为项目通用），`maxScore` 为满分。
- `POST /api/grade-rules/{ruleId}/versions`：建草稿版本，提交分数段 `bands:[{minScore,maxScore,grade}]`（左闭右开；段段相连不重叠不缺口；首段从 0 起、末段到满分止，否则拒收）。
- `GET  /api/grade-rules/{ruleId}/versions`、`GET /api/grade-rules/versions/{versionId}`。
- `POST /api/grade-rules/versions/{versionId}/publish`：**DRAFT→ACTIVE**，发布即冻结分数段，并自动把同规则旧生效版置 **DISABLED**（同规则同时刻仅一版生效）。
- `POST /api/grade-rules/versions/{versionId}/disable`：人工停用生效版。
- `POST /api/grade-rules/versions/{versionId}/trial`：`{"score":72}` 按版本试算等级，不写成绩。
- 定级只认项目当前 ACTIVE 版本（优先科目精确匹配，其次通用规则）；成绩行永久记下 `ruleVersionNo`，规则以后升级不改变历史口径。

### 10. 成绩录入与定级

- `POST /api/scores/import/preview` → `POST /api/scores/import`：按批次批量录卷面分（按报名号定位名册，须属于本批次）。预检逐条列可录入/跳过与定级结果、规则版本；执行时任一不通过**整批拒收、不落半条**，已录成绩不允许重复导入（改分必须走复核）。
- `GET  /api/scores/batch/{batchId}`、`GET /api/scores/{id}`。
- `GET  /api/scores/change-logs?batchId=`、`GET /api/scores/{id}/change-logs`：改分前后留痕（只追加）。

### 11. 成绩复核（窗口校验 / 超窗自动关闭 / 结论回写通知）

- `POST /api/reviews/apply`：`{"scoreId":...}` 或 `{"batchId":...,"regNo":...}` 提交复核。仅窗口 **OPEN** 可提交；未配置 / 未开始 / 已关闭分别返回可读原因（`REVIEW_WINDOW_*`）；同一成绩处理中不可重复提交；成绩置 `REVIEWING`。
- `POST /api/reviews/{id}/conclusion`：`{"approved":true,"newScore":85,"conclusion":"..."}` 复核通过——按**原定级规则版本**重算等级、改分，前后分数/等级写入复核单和改分日志，并发站内通知；`{"approved":false,"conclusion":"..."}` 驳回维持原分，同样通知。
- 超窗自动关闭：窗口（批次配置的 `reviewEndAt`）过后仍 `SUBMITTED` 的申请，定时任务（每分钟，`evops.review.close-cron` 可调）自动置 `CLOSED`、成绩解锁、通知学员；也可 `POST /api/reviews/close-expired` 手动立即执行。
- `GET /api/reviews?status=`、`GET /api/reviews/batch/{batchId}?status=`、`GET /api/reviews/{id}`。
- 通知：`GET /api/notifications?regNo=&unreadOnly=`、`POST /api/notifications/{id}/read`（本版站内落库，短信等外发渠道下版再接）。

### 11-补. 复核材料与申请流转变分离（grade-review-1）

**申请流转与材料阅读分开处理**：复核单的提交/结论/关闭是一条线；材料进入、附件换版、阅读是另一条线，阅读只追加阅读记录，**不改变复核单状态、不受复核窗口影响**。

- **材料进入留档、按时间排列**：`POST /api/review-materials`（`reviewId,title,materialType(DOC/PHOTO/FORM/OTHER),fileRef,contentHash,fileSize,originalFilename,content?,internalAnnotation?`；附件与正文至少给一个）。材料列表按进入 evops-base 的时间排列：`GET /api/review-materials?reviewId=`、`GET /api/review-materials/{id}`。
- **附件版本替换留摘要**：`POST /api/review-materials/{id}/replace`（新 `fileRef/content` + 必填 `reason`）。旧版不删除，置 `SUPERSEDED` 并保留摘要（文件名/大小/哈希/替换原因/钟点），追加 ACTIVE 新版；版本链 `GET /api/review-materials/{id}/versions`，阅读记录 `GET /api/review-materials/{id}/reads`。
- **窗口关闭只禁止新受理**：窗口 CLOSED 后 `POST /api/reviews/apply` 返回 `REVIEW_WINDOW_CLOSED`；历史申请、结论、材料版本与阅读照常可查可阅（含超窗 CLOSED 的申请）。
- **审核岗写明依据**：`POST /api/reviews/{id}/conclusion` 的 `conclusion`（结论依据）对通过/驳回都**必填**（`REVIEW_CONCLUSION_BASIS_REQUIRED`），通知正文随附"结论依据"。
- **内部批注**：`POST /api/review-materials/{id}/annotation?annotation=` 写内部批注，仅内部管理视图可见。
- **通知回执挂回原申请**：结论通知与超窗关闭通知均带 `reviewId`；学员详情视图直接挂回该申请的复核类回执（查询限定 `REVIEW_RESULT/REVIEW_CLOSED`，不与复用同表的申诉通知串线）。
- **导出不带内部批注**：管理端 `GET /api/reviews/batch/{batchId}/export.csv`（含结论依据、材料各版摘要，历史不受窗口影响），严格不含 `internalAnnotation`。

**学员本人窗口**（`X-Self-Code: 报名号`，与违纪自查同一机制；只能读本人资料，非本人的申请/材料/通知统一按不存在处理，不暴露存在性）：

- `GET /api/reviews/my?status=`：本人申请（脱敏：姓名掩码，不含审核岗经办信息）。
- `GET /api/reviews/my/{id}`：本人详情——含按时间排列的材料与版本摘要（旧版只给摘要）、挂回原申请的通知回执；**不含内部批注、不下发文件引用/正文**。
- `POST /api/reviews/my/materials/{materialId}/read`：阅读本人材料当前版并留痕（SELF，独立于流转，窗口关闭后仍可阅读）。
- `GET /api/reviews/my/notifications?unreadOnly=`、`POST /api/reviews/my/notifications/{id}/read`：本人回执与已读（非本人通知按不存在处理）。
- `GET /api/reviews/my/export.csv`：导出本人复核资料（姓名掩码、含材料版本摘要、不含内部批注）。

材料三表 `t_review_material / t_review_material_version / t_review_material_read` 已纳入工作区快照/回退（schema 版本 `grade-review-1`）。

## 同校混编整改工作区（mix-1）

针对"同校学员相邻而坐、抄袭"的监考通报：在现有编排上加同校错开约束，同校按比例摊到多个考场，座次表可打印，重排整批进行不留半新半旧，并给出同校相邻统计供巡考抽查。种子与座次由 **evops-base** 落库保存。

### 12. 名册学校信息

- 报名导入 / 单项中 `school` 为所属学校（可空，空按未知处理，不计同校相邻）；`GET /api/registrations?batchId=` 返回含 `school`。
- 学校可勘误：`POST /api/registrations/{id}/corrections`，`fieldName=SCHOOL`。

### 13. 混编算法模块（模块 + 版本 + 状态）

与等级规则同一套版本模式：算法按项目建档（`projectCode`，缺省 `DEFAULT`），版本 **DRAFT→ACTIVE→DISABLED**，发布即冻结参数 JSON，同模块同时刻仅一版 ACTIVE，停用不删、历史编排保留版本号。

- `POST /api/mix-algorithms`、`GET /api/mix-algorithms?projectCode=`、`GET /api/mix-algorithms/{id}`。
- `POST /api/mix-algorithms/{id}/versions`：建草稿，参数为
  `{"strategy":"SCHOOL_SPREAD","minSchoolGap":1,"spreadRooms":true,"maxSameSchoolPerRoom":null,"tries":40}`
  （间隔须 ≥1、tries 1~1000，非法整批拒收）；`GET .../versions`、`GET /api/mix-algorithms/versions/{versionId}`。
- `POST /api/mix-algorithms/versions/{versionId}/publish`（旧 ACTIVE 自动 DISABLED）、`POST .../disable`。
- 编排取批次所属项目的当前 ACTIVE 版本；项目无生效版本时用内置默认参数（结果中标 `BUILTIN_DEFAULT`）。

### 14. 编排 / 整批重排 / 抽查统计

- 首次编排仍为 `POST /api/arrange/preview` → `POST /api/arrange`，请求可带 `seed`（不传由系统取确定性种子）。预检与执行共用同一方案，返回 `mixVersionNo / seed / roomCount / sameSchoolAdjacentCount / adjacentPairs`。
- **整批重排**：`POST /api/arrange/rearrange/preview` → `POST /api/arrange/rearrange`。
  单一事务内删旧证、按新方案整批重建；要么全新要么整批回滚，**不留半新半旧**。
  证一旦打印 / 发放即锁定座次（`REARRANGE_TICKETS_USED`），防止纸面与座次不符。
- 编排记录（种子 + 座次由 evops-base 落 `t_arrangement_run`，只追加、重排只切 `is_current`）：
  `GET /api/arrange/runs?batchId=`（列表不带座次大字段）、`GET /api/arrange/runs/current?batchId=`、`GET /api/arrange/runs/{id}`（含座次快照与相邻明细 JSON）。
- 同校相邻口径：同一考场内座位号连续且两人同校；返回总对数与逐对明细（考场号、座位 A/B、学校、两人报名号姓名），供巡考抽查。

### 15. 座次表导出打印

- `GET /api/arrange/seating-chart?batchId=`：打印用 HTML，每个考场单独一页（浏览器直接打印 / 另存 PDF），含考场号、座位号、准考证号、报名号、姓名、学校与签到栏，抬头标注混编版本、种子与同校相邻对数。
- `GET /api/arrange/seating-chart.csv?batchId=`：同内容 CSV（UTF-8 BOM，Excel 直开）。
- 未编排批次导出返回 `ARRANGEMENT_RUN_NOT_FOUND`。

> 新表 `t_mix_algorithm / t_mix_algorithm_version / t_arrangement_run` 已纳入工作区快照与回退；schema 版本号 `mix-1`。

## 兼职监考聘任与结算工作区（appoint-1）

把"聘任和结算"做进 evops-base：**监考随排班生成聘任 → 场次结束签到计津贴（角色不同标准不同）→ 缺勤不计费 → 换人留替换记录 → 月底一键出结算单（人事看全量、本人看自己）→ 确认锁定、要改走调整**。兼职老师对费有据可查，不再和人事窗口各执一本账。聘任记录分**模块（主表）/ 版本（只追加）/ 状态（当前版本）**三段。

视图口径：所有接口仍走 Shiro HTTP Basic；请求头 **`X-Self-Code=<监考人员编码>`** 即"本人视图"（只返回本人聘任/版本/替换/结算明细），不传为人事全量视图；经办人仍由 `X-Operator` 记录。

### 16. 监考人员与津贴标准（角色不同标准不同）

- `POST /api/invigilators`、`GET /api/invigilators`：兼职监考人员（编码、姓名、默认角色 `CHIEF 主考 / INVIGILATOR 监考 / PATROL 巡考`、`staffKind`）。
- `POST /api/invigilators/allowance-rates`、`GET .../allowance-rates?projectCode=&roleCode=`：按**项目 + 角色 + 生效区间**配置单场津贴；区间重叠或到期缺标准在生成聘任时报 `RATE_AMBIGUOUS / RATE_NOT_CONFIGURED`。
- 津贴口径在**生成聘任（或换人接任）时冻结**进聘任版本（rateId/rateCode/金额），以后调价不改历史聘任。

### 17. 监考随排班生成聘任（两步预检）

- `POST /api/appointments/shifts/preview` → `POST /api/appointments/shifts`：请求 `{batchId, shiftStart?, shiftEnd?, assignments:[{roomId, roleCode?, invigilatorCode}]}`。
  按批次把监考排进考场角色场次，**一条在岗排班生成一条聘任**（`t_invigilation_shift` + `t_appointment` + 首版 CREATE `t_appointment_version`）。
  - 预检逐条列可生成 / 跳过与命中标准、金额；同考场可排多名同角色监考；同人同批次重复排班、同人多岗、考场不属于本考点等结构性问题**整批拒收**；已排班场次幂等跳过。
- 聘任号 `AP + 批次编码 + 4 位流水`；排班与聘任 1:1。

### 18. 聘任记录版本 / 状态（只追加）

- 状态机：**APPOINTED 已聘任 → CHECKED_IN 已签到（计费）**；旁路 **ABSENT 缺勤（不计费）/ REPLACED 已换人 / CANCELLED 已取消**。
- `POST /api/appointments/{id}/check-in`（可带 `signInAt`，缺省当前时间）：**场次结束签到后才计津贴**（billable=true），金额沿用聘任时冻结口径，结算周期按签到月归属。
- `POST /api/appointments/{id}/absent?reason=`：未签到记缺勤，**缺勤不计费**；已签到（已计费）不能改记缺勤。
- `POST /api/appointments/{id}/cancel?reason=`：场次取消，不计聘任计费（已签到计费的不能取消）。
- 每次状态变化**追加一版** `t_appointment_version`，主表只切当前版本号与状态；`GET /api/appointments/{id}/versions` 查完整版本链。

### 19. 换人（留替换记录）

- `POST /api/appointments/{id}/replace`：`{toInvigilatorCode, reason}`。
  一次换人：原持聘人追加 **REPLACED 版（不计本场费）**、接任者追加 **APPOINTED 新版**（按接任角色/场次日期重新冻结津贴），写一条 `t_appointment_replacement`（谁→谁、原因、经办、钟点），排班持聘人同步更新；接任者本批次已有在岗聘任则拒收。
- `GET /api/appointments/replacements?batchId=`、`GET /api/appointments/{id}/replacements`：替换记录（本人视图只返回涉及本人者）。

### 20. 月底一键结算单（人事全量 / 本人自己；确认锁定、调整留痕）

- `POST /api/settlements/preview` → `POST /api/settlements`：请求 `{month:"yyyy-MM"（缺省当月）}`。
  按**签到月**汇总区间内已签到（CHECKED_IN / billable）且未锁定的聘任，**一人一行**（场次、金额合计）。先预检看全量口径，确认生成 **DRAFT 草稿**；草稿可重复生成按最新签到重算。
- `POST /api/settlements/{id}/confirm`：**确认后锁定**——计费聘任置 `settled` 并挂结算单（之后不能再签到/换人/取消），已确认月份不能重新生成结算单。
- 查询：`GET /api/settlements[?month=]`；`GET /api/settlements/{id}`（人事返回全部明细，带 `X-Self-Code` 仅返回本人那一行，响应标 `view=HR_ALL/SELF`）；`GET /api/settlements/my-items[?month=]` 本人跨单只看自己；`GET /api/appointments[?batchId=&status=&invigilatorCode=]` 本人视图强制只看本人。
- **要改走调整**：`POST /api/settlements/{id}/adjustments` `{invigilatorCode, adjustAmount(可正可负), reason}`，只追加 `t_settlement_adjustment`，本人明细与结算单应付额累加变动；`GET /api/settlements/{id}/adjustments` 查台账。未确认草稿不接受调整（直接重算即可）。

> 新表 `t_invigilator / t_invigilator_allowance_rate / t_invigilation_shift / t_appointment / t_appointment_version / t_appointment_replacement / t_settlement / t_settlement_item / t_settlement_adjustment` 已纳入工作区快照与回退；schema 版本号 `appoint-1`。

## 保密室双人复核登记链工作区（vault-1）

把"双人双锁"从自觉变成系统约束：**进出刷证 → 第二人复核确认才算生效 → 在室清单实时可查 → 卷箱出入跟人员进出挂钩 → 异常时段自动标记给保卫**。登记写进去就不能改，纠错一律走补充说明（只追加）；三个月纸面存量两步导入。出入登记分**模块（主表）/ 版本（只追加）/ 状态（当前版本）**三段，与聘任记录同一套版本模式。

### 21. 准入人员 / 卷箱 / 允许进出时段

- `POST /api/vault/persons`、`GET /api/vault/persons?status=`：准入人员（编码、姓名、**刷证卡号**、职务）；`POST /api/vault/persons/{id}/disable|enable`：停用后不能登记也不能当复核人，历史登记保留。
- `POST /api/vault/boxes`、`GET /api/vault/boxes`：卷箱建档（箱号唯一），箱号先建档再登记出入。
- `GET /api/vault/access-window`、`POST /api/vault/access-window`：允许进出时段（`{"start":"07:00","end":"19:00"}`，起止成对、起早于止；缺省 07:00–19:00）。落在窗口外的登记自动标记 `abnormal=true`。

### 22. 出入登记模块（刷证 → 待复核 → 第二人确认生效）

- `POST /api/vault/access/swipe`：刷证登记 `{"cardNo":"CARD-001"（或 personCode）,"direction":"IN|OUT","occurredAt":"可空缺省当前","boxes":["BX-001"],"remark":""}`，落库 **PENDING 待复核**，不生效、不计入在室清单；同人同向同钟点防重（`ACCESS_DUPLICATE`），未来时间拒收。
- `POST /api/vault/access/{id}/review`：`{"reviewerCode":"K002","approved":true,"comment":""}` 第二人复核——复核人须为**登记者本人以外**的在册启用人员（`REVIEWER_SAME_AS_PERSON`），确认才 **CONFIRMED 生效**；`approved=false` 驳回必须写意见，**REJECTED 永不生效**；已复核的登记不能再复核。
- 状态机：**PENDING 待复核 → CONFIRMED 已生效 / REJECTED 已驳回**；每次流转追加一版 `t_vault_access_version`（CREATE/CONFIRM/REJECT/IMPORT），主表只切当前版本号与状态。
- `GET /api/vault/access?status=&direction=&personCode=&from=&to=`、`GET /api/vault/access/{id}`、`GET /api/vault/access/{id}/versions`（版本链）、`GET /api/vault/access/{id}/boxes`（挂钩卷箱明细）。

### 23. 在室清单（实时可查）

- `GET /api/vault/access/in-room`：在室人员——只按**已生效**登记推导，每人最后一条生效方向为 IN 即在室，带进室时间与登记号。
- `GET /api/vault/boxes/in-room`：在室卷箱——按已生效登记挂钩的卷箱明细推导，带经手人（谁带进来的）。

### 24. 卷箱出入跟人员进出挂钩

- 卷箱不能单独登记出入，必须挂在一条人员出入登记上（`boxes:[...]`），方向与人一致，随登记复核生效。
- 位置校验按已生效台账：带进要求箱当前不在室（`BOX_ALREADY_IN_ROOM`），带出要求箱当前在室（`BOX_NOT_IN_ROOM`）；刷证时校一次，**确认生效前再校一次**（等待期间台账可能已变）。

### 25. 异常时段自动标记给保卫

- 发生时间落在允许窗口外的登记自动 `abnormal=true`；**生效时**（复核确认或导入生效）自动生成 `OFF_HOURS` 告警给保卫，未生效前不产生告警。
- `GET /api/vault/alerts?status=NEW|ACKED`：保卫查看；`POST /api/vault/alerts/{id}/ack?note=`：签收留痕（签收人取 `X-Operator`），重复签收拒收。

### 26. 登记不可改，纠错走补充说明

- 登记内容（人/方向/钟点/卷箱）写入后不改，没有更新/删除接口；纠错一律 `POST /api/vault/access/{id}/notes` `{"noteType":"CORRECTION|SUPPLEMENT","content":"..."}`，只追加、不覆盖历史；`GET /api/vault/access/{id}/notes` 查痕迹。

### 27. 三个月纸面存量导入（两步）

- `POST /api/vault/access/import/preview` → `POST /api/vault/access/import`：条目 `{personCode, direction, occurredAt:"yyyy-MM-dd HH:mm", boxes?, remark?}`。
- 预检逐条列可导入/跳过；人员或卷箱未建档、方向/时间非法、未来时间、文件内重复等结构性问题**整批拒收、不落半条**；与库内同键（人+方向+钟点）的逐条幂等跳过，重复导入安全。
- 历史既成事实：落库即 **CONFIRMED**（版本 IMPORT，单版生效），卷箱位置按纸面原样记录；异常时段同样自动标记并告警保卫。

> 新表 `t_vault_person / t_vault_box / t_vault_access / t_vault_access_version / t_vault_box_move / t_vault_access_note / t_vault_alert / t_vault_config` 已纳入工作区快照与回退；schema 版本号 `vault-1`。

## 缺考裁定与成绩快照工作区（absence-1）

针对"同一个缺考、两个窗口两种答复"的投诉：缺考裁定收口到 evops-base 单一权威来源——**按签到和违纪记录自动标缺考 → 双人复核出最终裁定 → 裁定决定成绩有没有效 → 有效进合格名单、无效写原因衔接下次安排 → 统计只认最终裁定，裁定有变更就重算**。缺考标记分**模块（主表）/ 版本（只追加）/ 状态（当前版本）**三段，与聘任、出入登记同一套版本模式。

### 28. 考生签到（现场签到即生效；补录走审批）

- `POST /api/absence/checkins`：`{batchId, regNo, checkInAt?, remark?}` 现场签到，登记即生效（CONFIRMED）；一人一批次一条生效签到（`CHECKIN_EXISTS`）。
- `POST /api/absence/checkins/supplement`：`{batchId, regNo, checkInAt, reason}` 补录申请，落库 **PENDING 待审批**，不生效；已有生效签到或待审批申请时拒收。
- `POST /api/absence/checkins/{id}/approve`：`{approved, comment?}` 审批——审批人取 `X-Operator`，**须非补录提交人**（`SUPPLEMENT_SELF_APPROVE`）；批准才 CONFIRMED 生效，驳回必须写意见（REJECTED 永不生效）。批准生效前再校一次是否已有生效签到。
- **补录批准的连锁**：对该生已生效的缺考（ABSENT）无效裁定**自动发起变更**（回到待复核，拟恢复成绩有效，仍须双人复核确认）。
- `GET /api/absence/checkins?batchId=&status=&regNo=`、`GET /api/absence/checkins/{id}`。

### 29. 违纪记录（自动标缺考的输入）

- `POST /api/absence/violations`：`{batchId, regNo, violationType, description?, occurredAt?}` 直接登记；类型 `CHEAT 作弊 / IMPERSONATE 替考 / PROHIBITED 携带违禁品 / DISORDER 扰乱秩序 / OTHER 其他`。
- `GET /api/absence/violations?batchId=&regNo=`。

### 30. 缺考标记模块（自动扫描 + 手动标记，登记即待复核）

- `POST /api/absence/scan/preview` → `POST /api/absence/scan`：`{batchId}` 两步扫描——名册中**无生效签到**的自动拟标 `ABSENT 缺考`、**有违纪记录**的自动拟标 `VIOLATION 违纪`，均落 PENDING；已有在办/生效同类型标记的逐条跳过（幂等，重复扫描安全）。
- `POST /api/absence/marks`：`{batchId, regNo, markType, reason, nextArrangement?, nextBatchId?}` 手动标记——**弃考（ABANDONED）走这里**；缺考/弃考/违纪三类分清（`markType=ABSENT/ABANDONED/VIOLATION`）。
- 裁定号 `QK + 批次编码 + 4 位流水`；**一人一裁定**：同一学员仅一条在办/生效裁定（不限类型，`MARK_EXISTS`），要调整走复核或变更，从根上杜绝"两个窗口两种答复"。

### 31. 双人复核与最终裁定（结论决定成绩有效性）

- `POST /api/absence/marks/{id}/review`：`{approved, comment?, markType?, scoreValid?, invalidReason?, nextArrangement?, nextBatchId?}`——复核人取 `X-Operator`，**须非标记经办人**（`REVIEWER_SAME_AS_OPERATOR`）；可按需修正类型与拟定结论；驳回必须写意见。
- **确认生效（CONFIRMED 即最终裁定）**，同一事务内：回写成绩 `t_exam_score.valid` → 逐人逐笔写**成绩快照**（`t_score_snapshot`，前后状态只追加）→ **重算批次统计** → 站内通知本人（`ABSENCE_RULING`）。
- 结论为成绩无效时**必须写明原因并衔接下次安排**（`SCORE_INVALID_DETAIL_REQUIRED`）；确认缺考无效前若该生已有生效签到则拒收（`CHECKIN_EXISTS`，两个窗口同一口径）。
- **驳回**：新标记驳回即终结（REJECTED 永不生效）；变更轮驳回自动**恢复原裁定结论**（版本留痕）。

### 32. 变更裁定（已生效裁定回到待复核）

- `POST /api/absence/marks/{id}/change`：`{scoreValid, markType?, invalidReason?, nextArrangement?, nextBatchId?, reason}`——仅 CONFIRMED 可变更；可一并纠正类型（缺考/弃考分清）；变更回到 PENDING 再走双人复核，确认后成绩有效性翻转、写快照、**重算统计**、通知本人。
- `GET /api/absence/marks?batchId=&status=&markType=`、`GET /api/absence/marks/{id}`、`GET /api/absence/marks/{id}/versions`（版本链全程留痕）。

### 33. 合格名单 / 统计 / 成绩快照

- `GET /api/absence/pass-list?batchId=`：**成绩有效即进名单**（无生效的无效化裁定），每行带等级与规则版本，按等级汇总计数；合不合格由等级规则表达，裁定只管有效/无效。
- `GET /api/absence/stats?batchId=`：批次统计——报名/签到/补录待审/待复核标记/**缺考/弃考/违纪分类型计数**/成绩有效无效/名单人数/等级分布；**只认最终裁定**（每个标记最新一条 CONFIRMED 版本），每批次一行当前值，`generation` 随重算递增。
- `POST /api/absence/stats/recompute`：手动重算（平时由裁定确认、签到生效、补录批准自动重算）。
- `GET /api/absence/score-snapshots?batchId=&regNo=`：成绩快照——每笔裁定确认时该生成绩的前后状态（分数/等级/规则版本/有效标记 before→after、关联裁定号），只追加，两个窗口查同一份。
- 通知：`GET /api/notifications?regNo=` 可查裁定结论通知（与成绩复核通知同一处）。

> 新表 `t_examinee_checkin / t_violation_record / t_absence_mark / t_absence_mark_version / t_score_snapshot / t_absence_stat` 已纳入工作区快照与回退；`t_exam_score` 加列 `valid`（裁定生效回写，成绩导入时若已有生效无效裁定则落库即无效）；schema 版本号 `absence-1`。

## 证书编号与验真工作区（cert-1）

解决"编号手工排出重号、发出的证收不回、验真没有的事"：**编号规则收口（年度+批次+流水、末位校验码）→ 系统发号唯一 → 发证只读、作废留记录 → 对外凭编号+姓名验真回有效性与批次**。编号规则与等级规则、混编算法同一套**模块 / 版本 / 状态**三段式，规则调整只管新证。用人单位下周开始接入，先小流量。

视图口径：内部管理走 `bootstrap/bootstrap`；对外验真窗口走专用账号 `employer/employer`（只能访问 `/api/cert-verify`）。

### 34. 编号规则模块（模块 + 版本 + 状态）

- `POST /api/cert-rules`、`GET /api/cert-rules?projectCode=`、`GET /api/cert-rules/{id}`：规则按项目建档（缺省 `DEFAULT`，助考传 `ZHUKAO`）。
- `POST /api/cert-rules/{ruleId}/versions`：建草稿版本，提交发号参数
  `{"prefix":"ZS","yearDigits":4,"batchDigits":2,"seqDigits":5,"checkEnabled":true}`
  （前缀 1~6 位大写字母/数字；年度 2/4 位；批次 1~4 位；流水 3~8 位，非法拒收）。
  编号形态 = `前缀 + 年度 + 批次 + 流水 + 末位校验码`，如 `ZS20260100001X`。
- 末位校验码采用 **ISO 7064 MOD 11-2**（数字字母扩展取值，余数 10 记为 `X`），可机读挡住抄错一位与邻位颠倒。
- `GET .../versions`、`GET /api/cert-rules/versions/{versionId}`。
- `POST /api/cert-rules/versions/{versionId}/publish`：**DRAFT→ACTIVE**，发布即冻结发号参数，同规则旧生效版自动 **DISABLED**（同规则同时刻仅一版生效）；`POST .../disable` 人工停用。
- **规则调整只管新证**：每张证永久记下 `rule_version_no` 与冻结参数；规则升级、流水加宽后老证编号不变。

### 35. 发证（系统发号，两步预检；流水重起不撞旧号）

- `POST /api/certificates/issue/preview` → `POST /api/certificates/issue`：
  请求 `{projectCode?, batchId?, certYear?(缺省今年), certBatch?(可由批次编码数字部分推导), items:[{holderName,idNo?,subject?,grade?}]}`。
  - 发号按 `规则+年度+批次` 维度发流水，`t_cert_seq` **行锁取号**，并发不重号；并以**库内已用最大流水兜底起跳**（`MAX(current_seq, 已用最大流水)`），序列重起 / 手工旧号补登后都不撞旧号。
  - 预检逐条列可发 / 跳过（同年度批次同人跳过、清单内重复跳过）与**拟发编号**；执行实发编号逐条带回。
  - 流水段用尽报 `CERT_SEQ_EXHAUSTED`（不落半条），提示发布更宽流水的新规则版本。
- **手工旧号补登** `POST /api/certificates/legacy`：旧证（收不回的）原样登记 `cert_no`（不重发）；能辨认年度/批次/流水时自动把该序列抬过旧号，之后发号跳过旧号段。
- **发证后只读**：证书无修改 / 删除接口；持证人信息、编号、规则版本永久冻结。

### 36. 作废（留记录，不删除）

- `POST /api/certificates/{id}/revoke`、`POST /api/certificates/by-no/{certNo}/revoke`：`{reason}`（必填）。
  ISSUED→REVOKED，只切状态并**追加一条** `t_certificate_revoke_record`（原因、经办、钟点），原证保留、信息仍只读；重复作废报 `CERT_ALREADY_REVOKED`。
- `GET /api/certificates/{id}/revoke-records`：查作废留痕。
- 查询：`GET /api/certificates?batchId=&projectCode=&status=&holderName=`、`GET /api/certificates/{id}`、`GET /api/certificates/by-no/{certNo}`（内部视图，含留档字段）。

### 37. 对外验真窗口（凭编号+姓名，回有效性与批次，不带隐私）

- `GET /api/cert-verify?certNo=&name=`（专用账号 `employer/employer`）：
  - 只回**证面公开项**：`found / valid / status(ISSUED|REVOKED|NOT_FOUND) / message / certNo / holderName / batchCode / batchName / subject / grade / projectCode / issuedAt`，**不含证件号等隐私字段**（响应 DTO 无此类字段）。
  - 编号不存在、编号抄错、**姓名不匹配统一 `found=false`**，不暴露编号是否存在，防枚举探测。
  - **作废证给明确提示**：`valid=false,status=REVOKED`，message「该证书已作废，不再有效（作废原因：…）」，与"查无此证"清楚区分。
- **小流量灰度**：`GET /api/cert-verify-config`、`PUT /api/cert-verify-config/{key}?value=`（内部账号）。
  - `VERIFY_ENABLED`（true/false）窗口总开关，关闭返回 `CERT_VERIFY_DISABLED`；
  - `VERIFY_RATE_PER_MINUTE` 每分钟上限（缺省 60，0 不限），超限返回 `CERT_VERIFY_RATE_LIMITED`。下周接入先小流量，观察后再调大。

> 新表 `t_cert_number_rule / t_cert_number_rule_version / t_cert_seq / t_certificate / t_certificate_revoke_record / t_cert_verify_config` 已纳入工作区快照与回退；schema 版本号 `cert-1`。

## 旺季报名条件规则引擎工作区（regcond-1）

把旺季报名条件从人工翻材料收口到 evops-base：**学历、年限、前置证书按级别配成核验项 → 提交时按提交时点生效的规则版本逐项比 → 不通过逐条给原因、存疑转人工 → 学员可申诉、流转全程留痕 → 合格后回写报名名册**。条件规则同样是**模块（主表）/ 版本（只追加）/ 状态（当前版本）**三段式，与等级规则、证书编号规则同一套版本模式。

口径约定（也是投诉答复口径）：
- **年限算头算尾**：从业起始日与提交日两天都计入，按 `[起始日, 提交日+1天)` 折算完整周年（另可配足月），不足一周期的零头不计。如要求满 3 周年，起始日 2023-10-03、提交日 2026-10-02 正好满足，晚一天即不满足。
- **前置证书连有效期一起验**：提交日须落在证书 `[validFrom, validTo]` 闭区间；未登记有效期按长期有效（规则可要求必须登记，否则存疑）；已作废、科目/等级不符为硬不符；库内查无此证、姓名对不上不直接误杀，**存疑转人工**。
- **规则变更按提交时点生效**：核验单永久记下命中的规则版本号与冻结核验项，之后规则升级不改变历史口径；发布日当天即对当天提交生效（按提交日口径）。
- **申诉期间不重复拦截**：在办申诉（待受理/处理中）期间，该学员同一核验项的新提交记 `APPEALING` 暂不拦截；申诉通过则按**人+项目+级别+核验项**维度永久豁免（跨规则版本保持）；驳回则恢复拦截。申诉终裁前不允许正式入册。

### 38. 条件规则模块（模块 + 版本 + 状态）

- `POST /api/condition-rules`、`GET /api/condition-rules?projectCode=&levelCode=`、`GET /api/condition-rules/{id}`：规则按**项目+级别**建档（同项目同级别唯一，学历/年限/前置证书按级别各配一套）。
- `POST /api/condition-rules/{ruleId}/versions`：建草稿版本，核验项 `items:[...]` 非法整批拒收；`GET .../versions`、`GET /api/condition-rules/versions/{versionId}`。
  - 学历项 `{"itemType":"EDUCATION","requiredEducation":"JUNIOR_COLLEGE"}`，编码 `NONE/PRIMARY/JUNIOR_HIGH/SENIOR_HIGH/JUNIOR_COLLEGE/BACHELOR/MASTER/DOCTOR`，按"不低于"比较。
  - 年限项 `{"itemType":"YEARS","minValue":3,"unit":"YEAR"}`（unit 可 `MONTH`，算头算尾）。
  - 前置证书项 `{"itemType":"PREREQ_CERT","requiredSubject":"助考一级","requiredGrade":null,"checkValidity":true,"validityRequired":false}`。
  - 同一类型 / 同一前置证书科目只能配一条；`name` 可空，缺省生成中文名。
- `POST /api/condition-rules/versions/{versionId}/publish`：**DRAFT→ACTIVE**，发布即冻结核验项，同规则旧生效版自动 **DISABLED**；`POST .../disable` 人工停用（停用版不可重新发布，要改新建版本）。

### 39. 提交核验（逐项比、逐条原因、存疑转人工、规则按提交时点生效）

- `POST /api/qualifications/submit`：提交材料 `{batchId?,regNo,name,idNo?,projectCode?,levelCode,education,workStartDate,prereqCertNo,submittedAt?}`（`submittedAt` 缺省当天）。
  - 取提交日项目+级别**当时生效**的规则版本逐项机器核验；每项 `PASS / FAIL / MANUAL / APPEALING`，FAIL/MANUAL 带可读中文原因与「要求/实际」对照。
  - 汇总：有硬不符（未被申诉/豁免放行）→ `REJECTED`；无硬不符但有存疑 → `MANUAL`；全部通过 → `QUALIFIED`（`qualifiedVia=AUTO/APPEAL/MANUAL_REVIEW`）。
  - 提交报文、命中规则版本号、逐项结果全部留档；`GET /api/qualifications/{id}`、`GET /api/qualifications/{id}/items`、`GET /api/qualifications?batchId=&status=&projectCode=&levelCode=`。
- `POST /api/qualifications/{id}/manual-review`：存疑人工结论 `{approved,comment}`——放行即合格（`MANUAL_REVIEW`），拒收必填原因。
- `POST /api/qualifications/{id}/enroll`：合格核验单回写报名名册（`t_registration`，备注带核验单号与规则版本号）；有在办申诉项时拒收（`QUALIFICATION_APPEAL_PENDING`）；已入册幂等。
- 证书侧配套：发证/旧号补登可带 `validFrom/validTo`（起晚于止拒收）；前置证书核验读取该有效期。

### 40. 学员申诉（流转留痕、申诉期间不重复拦截）

- `POST /api/qualification-appeals`：`{checkItemId,reason,evidence?,applicant?}`（或 `{qualificationId,itemKey,...}`）对不通过/存疑项申诉；落 **SUBMITTED** 并追加 CREATE 版本，核验项转 `APPEALING`、核验单暂不拦截。
- `POST /api/qualification-appeals/{id}/accept?comment=`：受理，SUBMITTED→REVIEWING（ACCEPT 版本）。
- `POST /api/qualification-appeals/{id}/conclusion`：`{approved,conclusion}` 终裁——
  - 通过（APPROVE 版本）：该项按豁免放行，核验单回算合格（`qualifiedVia=APPEAL`，记下豁免申诉单）；申诉期间其他新提交挂同一申诉的核验单一并转合格；
  - 驳回（REJECT 版本，驳回必填意见）：恢复机器原始结论，相关核验单一并回到不通过/存疑。
  - 终裁即站内通知学员（`COND_APPEAL_APPROVED/REJECTED`，`GET /api/notifications` 可查）。
- 豁免按 **人（证件号优先，缺则报名号）+项目+级别+核验项键** 生效，规则升级后依然保持；已终裁项不能重复申诉，补充新材料请重新提交生成新核验单。
- `GET /api/qualification-appeals?status=&regNo=&personKey=`、`GET /api/qualification-appeals/{id}`、`GET /api/qualification-appeals/{id}/versions`（CREATE/ACCEPT/APPROVE/REJECT 版本链）。

> 新表 `t_condition_rule / t_condition_rule_version / t_registration_qualification / t_qualification_check_item / t_qualification_appeal / t_qualification_appeal_version` 已纳入工作区快照与回退；`t_certificate` 加列 `valid_from/valid_to`（老库平滑加列）；schema 版本号 `regcond-1`。

## 教研组卷抽选工作区（papersel-1）

教研组要求**多场并行、难度均衡、卷面不雷同、且事后能证明公平**。组卷随机种子与卷面指纹保存在 **evops-base**：按**知识点+难度档配比**从素材池随机抽，**套与套重合率压在阈值内、超阈自动换种子重抽**；每套存卷面指纹备查；抽取过程可复算。素材池**增删留记录、只下架不物理删**，历史卷面持有组卷时点快照不受影响。

### 41. 素材池（增删改只留痕、不物理删）

- `POST /api/paper-materials`：建档 `{materialNo?,projectCode?,knowledgePoint,difficulty,content,referenceAnswer?,remark?}`，编号缺省 `SC+yyMMddHHmmss+4位序列`；建档即算内容指纹 `SHA-256(知识点|难度档|题干|参考答案)`。
- `POST /api/paper-materials/{id}/update`：改内容/归类（空字段不改；参考答案传空串可清空），内容指纹随之重算，**原因必填**。
- `POST /api/paper-materials/{id}/disable`：下架（原因必填），素材转 `DISABLED`、**不物理删除**，组卷只抽 `ENABLED`；`POST /api/paper-materials/{id}/enable?reason=` 恢复。
- `GET /api/paper-materials?projectCode=&knowledgePoint=&difficulty=&status=`、`GET /api/paper-materials/{id}`、`GET /api/paper-materials/{id}/changes`：增删改台账（CREATE/UPDATE/DISABLE/ENABLE，前后快照 JSON，只追加）。
- 历史卷面存的是组卷时点的素材快照（id+编号+知识点+难度档+内容哈希），**池子事后增删改不影响历史卷面复算**；验平时另把清单与当前池子的差异（下架/内容修改条数）作为提示返回。

### 42. 抽选算法模块（模块 + 版本 + 状态）

与混编/等级规则同一套三段式：算法按项目建档，版本 **DRAFT→ACTIVE→DISABLED**，发布即冻结参数 JSON，同模块同时刻仅一版 ACTIVE。

- `POST /api/paper-sel-algorithms`、`GET /api/paper-sel-algorithms?projectCode=`、`GET /api/paper-sel-algorithms/{id}`。
- `POST /api/paper-sel-algorithms/{id}/versions`：建草稿，参数为
  `{"strategy":"KP_DIFFICULTY_QUOTA","maxOverlapRate":0.20,"maxRetries":50,"compareScope":"GROUP"}`
  （重合率须在 [0,1]、重抽上限 0~1000，非法整批拒收）；`GET .../versions`、`GET /api/paper-sel-algorithms/versions/{versionId}`。
- `POST /api/paper-sel-algorithms/versions/{versionId}/publish`（旧 ACTIVE 自动 DISABLED）、`POST .../disable`。
- 组卷取项目当前 ACTIVE 版本并把**参数快照**随卷面留档；项目无生效版用内置默认（结果标 `BUILTIN_DEFAULT`，重合率 20%、重抽上限 50）。

### 43. 多场并行组卷（配比抽题、超阈自动重抽、池子不足提示）

- 两步：`POST /api/paper-compose/preview`（不落库）→ `POST /api/paper-compose`（运行记录与各套卷面同一事务落库），请求
  `{projectCode?,paperCount,groupCode?,rootSeed?,quotas:[{knowledgePoint,difficulty,count},...]}`。
- 抽题口径：按配比逐档从"知识点+难度档"桶内洗牌取数，随机源仅来自种子；**根种子**显式优先，否则按 项目|套数|配比|池子指纹|历史运行次数 取确定性种子；**各套衍生种子 = 根种子+套号增量+重探次增量**，纯确定性。
- 多套串行抽选（套 0、套 1…），每抽完一套与此前各套算重合率（重合素材数/单套题量），**任一套与任一此前套超阈即自动换衍生种子重抽**；返回各套 `retries` 与运行记录的 `retrySummary`；重抽达 `maxRetries` 仍压不下返回 `OVERLAP_RETRY_EXHAUSTED`，整批拒收不落库。
- **池子不足先于抽选逐项报缺口**（`POOL_INSUFFICIENT`：知识点/难度档要多少、在池多少、缺口多少），整批不落库；余量紧张、发生重抽以 `warnings` 提示。
- 返回每套 `seed（衍生种子）/ selected（中选快照）/ paperFingerprint / overlapMaxRate / overlapDetail / retries`，以及 `rootSeed / poolFingerprint / poolSize`。

### 44. 卷面指纹与事后公平核验

- 卷面指纹 = `SHA-256(算法版本号|根种子|衍生种子|池子指纹|顺序中选素材 编号:内容哈希)`；池子指纹 = 组卷时点在池素材按 id 排序逐条摘要的 SHA-256。卷面、种子、池子任一处被改动都会导致复算不符。
- `GET /api/paper-compose/runs/{runId}/verify`：用运行记录里**冻结的参数、配比、根种子、池子快照**重新抽一遍，逐套比衍生种子、中选清单、卷面指纹三项，全符才 `fair=true`；快照与库存指纹不符、复算抛错等情况逐条给 `notes`。
- `GET /api/paper-compose/runs?projectCode=&groupCode=`（列表不带快照大字段）、`GET /api/paper-compose/runs/{runId}`（含配比/参数快照/池子快照/指纹）、`GET /api/paper-compose/runs/{runId}/papers`、`GET /api/paper-compose/papers/{paperId}`。
- 运行记录与卷面只追加，无修改/删除接口；种子与指纹均在 evops-base 落库可追溯，满足下周教研会过流程与事后验公平。

> 新表 `t_paper_material / t_paper_material_change / t_paper_sel_algorithm / t_paper_sel_algorithm_version / t_paper_sel_run / t_paper_sel_paper` 已纳入工作区快照与回退；schema 版本号 `papersel-1`。

## 热门考点容量控制与候补递补工作区（capacity-1）

针对**热门考点一分钟满员、后来的人直接流失、退位名额没人接**：用 **evops-base** 承接候补队列与递补通知——
**满员进队列；有人退位按序递补；通知带确认时限，超时不确认顺给下一位；递补成功才锁座位。**
窗口与时限**按考点（批次）配**，随版本冻结；队列位置对学员**透明**；超时只认**服务器钟点**，顺延通知不漏发；重复请求**不许插队**。

### 45. 容量规则模块（模块 + 版本 + 状态）

与混编/等级规则同一套三段式：规则按 考点+批次 建档（一个批次一条），版本 **DRAFT→ACTIVE→DISABLED**，发布即冻结参数 JSON，同模块同时刻仅一版 ACTIVE。

- `POST /api/capacity-rules`：建档 `{code?,name,batchId,remark?}`（编码缺省 `CAP+批次id`；同批次重复建档 `CAP_RULE_BATCH_EXISTS`）；`GET /api/capacity-rules?siteId=&batchId=`、`GET /api/capacity-rules/{id}`。
- `POST /api/capacity-rules/{id}/versions`：建草稿，参数为
  `{"windowStart":"2026-11-01T08:00:00","windowEnd":"2026-11-01T17:00:00","offerTimeoutMinutes":30,"capacity":200}`
  （窗口起止成对、起早于止或同时留空；确认时限 1~10080 分钟；容量正整数，非法整批拒收）；`GET .../versions`、`GET /api/capacity-rules/versions/{versionId}`。
- `POST /api/capacity-rules/versions/{versionId}/publish`（旧 ACTIVE 自动 DISABLED；刷新 `t_capacity_state` 配置快照与代次并**立即递补空位**，如调大容量）、`POST .../disable`（停用后不再入队/递补，排队人由超时兜底拒收并通知）。
- `GET /api/capacity-rules/{id}/state`：容量状态总览——生效版本、`windowStatus`（NOT_CONFIGURED/NOT_OPENED/OPEN/CLOSED，按服务器钟点）、容量、在册+待确认占座数、候补中/已锁座人数、余量，一页讲清。

### 46. 满员入队与位置透明（重复请求不插队）

- `POST /api/capacity/waitlist/join`：
  `{ruleId|batchId,regNo,name,phone?}`。仅**窗口 OPEN 且满员**才进队（未满给 `CAP_NOT_FULL` 余量提示；已在册 `CAP_ALREADY_ENROLLED`；窗口未开/已关分别 `CAP_WINDOW_NOT_OPENED/CLOSED`；无生效版 `CAP_NOT_ACTIVE`）。
- 一人一规则一条（`uk(rule_id,reg_no)`）：**WAITING/OFFERED 重复请求只回原序号原位置**（不新建、不重发通知）；已锁座 `CAP_ALREADY_SEATED`；**超时/退出后再排一律到队尾**（新序号，不复用旧号），绝不插回原位；被拒收者不能再排。
- `GET /api/capacity/waitlist/position?ruleId=|batchId=&regNo=`：真实顺位 `position`（候补中按 seq 的名次）、`aheadCount` 前面人数、`seq` 入队序号、状态、递补轮次与确认截止钟点——位置透明，不让人猜。
- `GET /api/capacity/waitlist?ruleId=|batchId=&status=`（管理队列清单，按入队序号）、`GET /api/capacity/waitlist/{id}/events`（JOIN/OFFER/CONFIRM/EXPIRE/CANCEL/REJECT 事件链，只追加）。

### 47. 退位 → 按序递补 → 限时确认 → 锁座 / 超时顺延

- `POST /api/capacity/waitlist/resign`：已在册/已锁座人员退位（名册转 `WITHDRAWN` 保留记录、不删除），系统在**同一事务**内按 seq 首位 WAITING → OFFERED，写确认截止点并发带时限通知。
- 递补通知 `OFFER` 明确写明确认截止钟点与"逾期顺给下一位"，`offerDeadline` 随通知落库；状态总览与位置查询都能看到。
- `POST /api/capacity/waitlist/confirm`：截止点前确认 → **回写报名名册（REG_REGISTERED）并锁座**（WAIT_OFFERED 不生成准考证，座位即名册名额；重复确认、非待确认状态分别给可读错误）。
- 超时判定**只用服务器钟点**：超截止点的确认直接 `CAP_OFFER_EXPIRED` 拒收，顺延在**独立事务**先落库（不随确认异常回滚）；本人收 EXPIRED 通知，下一位的 OFFER 同事务发出——**顺延双方通知不漏发**。
- `POST /api/capacity/waitlist/cancel`：候补中退出不顺延（本就没占座）；**待确认中退出立即释放并顺延**给下一位。
- `POST /api/capacity/waitlist/sweep-expired`：手动执行；另有 `WaitlistScheduler` 每分钟兜底（`evops.capacity.sweep-cron` 可配，默认每分钟），按规则独立事务处理——超时作废→窗口关/规则停用则拒收仍排队者并通知→否则补齐递补。
- `GET /api/capacity/notifications?regNo=&waitlistId=&unreadOnly=`、`POST /api/capacity/notifications/{id}/read`：QUEUED/OFFER/SEATED/EXPIRED/CANCELLED/REJECTED 六类通知（本版先落库，短信等外发渠道下版再接）；OFFER 必带 `offerDeadline`。

> 新表 `t_capacity_rule / t_capacity_rule_version / t_capacity_state / t_capacity_waitlist / t_capacity_waitlist_event / t_capacity_notification` 已纳入工作区快照与回退；schema 版本号 `capacity-1`。并发安全：入队/递补/确认/超时全部经 `t_capacity_state FOR UPDATE` 行锁串行，占座 = 在册未退位 + 待确认，杜绝超卖与重复递补。

## 违纪立案与案件封存工作区（discipline-1）

解决"违纪处理靠邮件来回、一期案子拖一个月、期限没人盯"：把流程做进 evops-base——
**监考上报附证据立案 → 限期出处理决定、超时系统自动催办 → 决定类型对应成绩作废/禁考、执行结果回写成绩档案
→ 撤销立案必须走复核、全程轨迹只追加 → 学员脱敏查自己案件 → 结案封存、封存记录仅授权岗可查。**
违纪案件分**模块（主表）/ 版本（只追加）/ 状态（当前版本）**三段，与缺考裁定、出入登记同一套版本模式。

账号口径：内部办案仍用 `bootstrap/bootstrap`（角色 `system`）；新增**违纪档案授权岗** `archiver/archiver`
（角色 `discipline_archiver`），**只能访问封存窗口 `/api/discipline/sealed/**`**，访问其余接口返回 403；
`employer` 不能访问封存窗口。

### 48. 监考上报附证据立案（立案即起算期限）

- `POST /api/discipline/cases`：`{batchId, regNo, violationType, description?, occurredAt?, assignee?, source?, evidences:[...]}`。
  违纪类型 `CHEAT 作弊 / IMPERSONATE 替考 / PROHIBITED 携带违禁品 / DISORDER 扰乱秩序 / OTHER 其他`；
  **上报必须附证据（至少一条）**：`{evidenceType,title,fileRef?,contentHash?,content?,collectedAt?}`，
  类型 `PHOTO/VIDEO/DOC/STATEMENT/OTHER`，情况说明必须有正文、其余需附件引用或文字内容；未来时间拒收。
  案件号 `WJ + 批次编码 + 4 位流水`；上报即 **REPORTED 已立案待决定**，同学员有在办案件不能重复立案（`CASE_EXISTS`）。
- 处理决定期限 = 上报时间 + 配置期限（缺省 168 小时），记入 `decision_deadline`，**只按服务器钟点判定超时**。
- `POST /api/discipline/cases/{id}/evidences`：立案后补充证据，**只追加**，不改不删已有证据；已撤销/已封存不能补。

### 49. 限期出处理决定（超时自动催办）

- `POST /api/discipline/cases/{id}/decision`：`{decisionType, decisionContent, banFrom?, banUntil?}`，
  类型 `WARNING 警告 / SCORE_VOID 成绩作废 / BAN 禁考 / SCORE_VOID_AND_BAN 成绩作废并禁考`；
  REPORTED→**DECIDED**，决定正文与类型冻结进版本，并站内送达学员。禁考区间成对校验，`banUntil` 空表示长期禁考。
- **超时自动催办**：`DisciplineUrgeScheduler` 每分钟扫描（`evops.discipline.urge-cron` 可配），
  超过 `decision_deadline` 仍 REPORTED 的案件自动催办承办人，首轮超时即催，其后按间隔（缺省每 24 小时）升级一轮；
  每轮只追加一条催办记录（站内落库，外发渠道下版再接）。也可 `POST /api/discipline/urge/sweep` 人工立即执行。
- `GET /api/discipline/urges?caseId=&status=&recipient=`、`POST /api/discipline/urges/{id}/read`。
- 期限/间隔/默认承办人可调：`GET /api/discipline/config`、`PUT /api/discipline/config/{key}?value=&remark=`
  （键 `DECISION_DEADLINE_HOURS / URGE_INTERVAL_HOURS / DEFAULT_ASSIGNEE`，小时数须正整数）。

### 50. 后果执行回写成绩档案（成绩晚到挂起自动回写）

- `POST /api/discipline/cases/{id}/execute`：DECIDED→**EXECUTED**，逐条生成并执行后果：
  - **成绩作废**：有成绩即回写成绩档案——`t_exam_score.valid=false` 并挂案件号锚点 `discipline_case_no`，后果记下作废前分数/等级；
  - **成绩尚未导入**：后果挂 **PENDING_SCORE**（不阻塞结案），成绩导入时在同一事务**自动回写作废**；
  - **禁考**：禁考区间决定即生效（EXECUTED）。仅 WARNING 的决定无成绩后果。
- 与缺考裁定同一口径：成绩导入预检/执行时若该学员有**生效的成绩作废决定**（DECIDED/EXECUTED/SEALED），
  成绩**落库即无效**（与缺考无效化取并集）；`GET /api/scores/batch/{batchId}` 可见无效标记与案件号。

### 51. 撤销立案走复核（轨迹留全）

- `POST /api/discipline/cases/{id}/revoke` `{reason}`：撤销必须申请，案件进 **REVOKE_PENDING 撤销复核中**，原决定暂停（期间导入的成绩暂不回写）。
- `POST /api/discipline/cases/{id}/revoke-review` `{approved, comment?}`：复核人取 `X-Operator`，**须非撤销申请人**（`REVIEWER_SAME_AS_APPLICANT`），驳回必填意见。
  - **通过 → REVOKED**：已执行的成绩作废**恢复成绩有效并清案件号**（该生另有缺考无效裁定时仍保持无效）、禁考区间作废、挂起项取消，全部后果置 **CANCELLED** 并通知学员；
  - **驳回**：回到申请前状态维持原处理，复核期间晚到的成绩**补回写作废**。
- `GET /api/discipline/cases/{id}/versions`：版本链 `CREATE/DECIDE/EXECUTE/REVOKE_APPLY/REVOKE_APPROVE/REVOKE_REJECT/SEAL` 只追加；
  `GET /api/discipline/cases/{id}/evidences|consequences` 查证据与后果台账。

### 52. 学员脱敏自查 + 结案封存授权

- 学员凭报名号请求头 **`X-Self-Code=<报名号>`** 查自己案件：
  `GET /api/discipline/my-cases`、`GET /api/discipline/my-cases/by-no/{caseNo}`。
  只回**脱敏**信息：掩码姓名（如 `张*`）、案件号、违纪类型、当前状态、决定结论（送达内容）、阶段轨迹与钟点；
  **不含证件号、上报监考、承办人、证据原文/附件、催办记录**；查别人的案件统一"查无此案"，不暴露存在性。
- `POST /api/discipline/cases/{id}/seal`：仅 **EXECUTED 已执行完结或 REVOKED 已撤销**的案件可**结案封存（SEALED）**，封存后只读。
- **封存记录只有授权岗能看**：独立只读窗口 `/api/discipline/sealed/cases`、`/cases/{id}`、`/cases/{id}/evidences|consequences|versions`，
  仅回已封存记录（含证据与完整轨迹），Shiro 链路限定 `system` 或 `discipline_archiver` 任一角色，其余已认证账号 403、未认证 401。

> 新表 `t_discipline_case / t_discipline_evidence / t_discipline_case_version / t_discipline_consequence / t_discipline_urge / t_discipline_config` 已纳入工作区快照与回退；`t_exam_score` 加列 `discipline_case_no`（老库平滑加列）；schema 版本号 `discipline-1`。

## 监考手册按批次发布工作区（handbook-1）

监考手册按**考试批次**发布岗位说明、签到地点与应急联系人。内容写入 evops-base 后先进入**待确认**状态，**负责人放行才生成打印版本**；后续调整**另建版本**并通知对应考点；**下载权限跟随考点范围**，**过期手册自动归档**；补发时**保留经办人、生效时刻和发布批次**。手册分**模块（主表）/ 版本（只追加）/ 状态（当前版本）**三段。

视图口径：所有接口仍走 Shiro HTTP Basic（`bootstrap/bootstrap`）；请求头 **`X-Site-Code=<考点编码>`** 即"考点视图"，只能查看 / 下载本考点手册，越权访问他考点返回 `HANDBOOK_SITE_FORBIDDEN`；不传为考务全量视图。经办人由 `X-Operator` 记录。

### 53. 写入手册（先待确认）

- `POST /api/handbooks`：`{batchId, postDescription, signInLocation, emergencyContact, emergencyPhone, expireAt?}`。
  一个批次一本手册（重复建档 `HANDBOOK_ALREADY_EXISTS`），写入即 **PENDING_CONFIRM**，首版 V1（CREATE）；`expireAt` 为过期时刻（可空，空不自动过期）。
- `GET /api/handbooks`、`GET /api/handbooks/{id}`：列表 / 详情（带 `X-Site-Code` 仅回本考点）。
- 待确认手册不提供下载（`HANDBOOK_NOT_RELEASED`）。

### 54. 负责人放行（生成打印版）

- `POST /api/handbooks/{id}/release`：可带 `{"effectiveAt":"..."}`，缺省取服务器当前钟点。
  PENDING_CONFIRM → **RELEASED**，**放行时生成并冻结打印版 HTML**（含发布批次号、版本、岗位说明、签到地点、应急联系人、生效时刻、经办人）；同时生成一个发布批次 **R1**（INITIAL）并向对应考点发 **RELEASE** 通知。
- `GET /api/handbooks/{id}/print[?releaseId=]`：下载打印版（浏览器直接打印 / 另存 PDF）。
  缺省取当前生效版本冻结的打印版；仅 RELEASED 可下载，归档后 `HANDBOOK_ARCHIVED`。

### 55. 调整另建版本并通知考点

- `POST /api/handbooks/{id}/adjust`：`{postDescription?, signInLocation?, emergencyContact?, emergencyPhone?, reason(必填), expireAt?}`，
  **另建 V2（ADJUST）先待确认**，留空字段沿用上一版；已有待确认稿未放行时由新稿顶替（SUPERSEDED），同一时刻只留一份待确认稿。
- 调整稿放行：旧生效版置 **SUPERSEDED**，发布批次递增 **R2**（ADJUST），向考点发 **ADJUST** 通知；历史打印版原样保留、不改写。
  调整稿待确认期间手册保持 RELEASED，`/print` 缺省仍返回最近已放行版本的冻结打印版（新版放行后才切换）。

### 56. 发布批次 / 补发（保留经办人、生效时刻、发布批次）

- `GET /api/handbooks/{id}/releases`：发布批次台账（INITIAL / ADJUST 各开新批次 R1、R2……）。
- `POST /api/handbooks/{id}/reissue`：`{originReleaseId?, reason?}`（缺省补发最近正式发布批次）。
  补发生成 **REISSUE** 记录：**沿用原发布批次号（如同为 R2）与原生效时刻、原经办人**，补发经办人另记 `reissuedBy`、补发钟点另记，并向考点发 **REISSUE** 通知；补发打印页带补发抬头。

### 57. 考点通知 / 过期自动归档

- `GET /api/handbooks/{id}/notices`、`POST /api/handbooks/notices/{noticeId}/read`：RELEASE / ADJUST / REISSUE / ARCHIVE 通知链（NEW→READ），只追加。
- **过期自动归档**：服务器钟点每分钟扫描，`expire_at` 已过且未归档者置 **ARCHIVED**（版本一并置 ARCHIVED）并通知考点；
  `POST /api/handbooks/{id}/archive?reason=` 可人工归档。归档后不再提供下载，也不能再调整。
- `GET /api/handbooks/{id}/versions`：完整版本链（列表不带打印 HTML 大字段）。

> 新表 `t_invigilation_handbook / t_handbook_version / t_handbook_release / t_handbook_notice` 已纳入工作区快照与回退；schema 版本号 `handbook-1`。
