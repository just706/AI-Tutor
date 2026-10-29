# 阶段四首轮试点执行材料

总体进度和验收仍由 [Development](../../docs/Development.md#阶段-4验证实际效果) 维护。这里仅说明首轮材料、现场步骤和记录契约。

## 范围与当前状态

首轮是单组可行性试点，建议4–6名 Java 初学者，每人完成 ArrayList 索引与修改、HashMap 键值与覆盖两个主题。目标是发现任务能否完成、哪里卡住、反馈与引用哪里有错，并描述独立测验表现。用户已选择先进行个人走查，4–6名初学者试点暂不招募。2026-10-26 本轮个人走查按用户同意收尾，已有普通聊天与部分输出作答，未完成完整独立测验、教材任务及延后采集，详见 [收尾报告](stage4-self-test-results.md)。个人走查使用单独目录与self_test模式，不混入初学者样本，也不据此形成学习效果结论。

材料是项目自编短资料，不冒充正式教材；限定 Java 17、合法列表索引、完全相同字符串键等边界。`stage4-pilot.json` 固定了教材、任务、12题的代码、答案和逐点评分细则。三轮各主题2题，每题0–2分，主题总分4分。题面代码可以实际执行核验答案；这不能证明题目难度相等、教学适切或生成反馈正确。

当前源材料与题目尚待人工审阅。确认没有歧义、答案泄露、错误边界或评分细则冲突后，评分者在记录的 `review` 中填写真实审阅人、带时区时间、说明和 approved/rejected。工具不代填人工判断。阶段二内容准入仍未通过，本轮不扩展为无监督的自动循序教学试用。

## 生成与核验

在项目根目录执行，输出目录须为仓库外的新目录，父目录须已存在；已有文件不会被覆盖：

```powershell
node backend/scripts/verify-stage4-materials.mjs --output-dir "$env:TEMP/ai-tutor-stage4-material-check"
node backend/scripts/evaluate-stage4.mjs init --output-dir "$env:TEMP/ai-tutor-stage4-pilot-kit"
node backend/scripts/evaluate-stage4.mjs init --mode self_test --output-dir "$env:TEMP/ai-tutor-stage4-self-test"
node --test backend/scripts/evaluate-stage4.test.mjs
```

第一条命令使用本机 Java 17 执行12道题的题面代码，保存实际输出和材料SHA256，不连接数据库或模型。第二条命令生成如下文件：

| 文件 | 用途 |
| --- | --- |
| `arraylist-source.txt`、`hashmap-source.txt` | 学习阶段上传的自编资料；前测前不展示 |
| `*-task.md` | 学习阶段的固定任务 |
| `*-pre.md`、`*-immediate.md`、`*-delayed.md` | 独立前测、即时和延后测验，仅到相应阶段才发放 |
| `facilitator-only.md` | 答案与评分细则，仅主持人和评分者可见 |
| `participant-template.json` | 单名学习者的空白记录结构，ID使用P01等匿名代号 |
| `records.json` | 实际记录，初始化参与者数组为空，所有观测待采集 |
| `report.json` | 初始化报告，状态为not_collected，未知均值为null |
| `findings-template.md` | 按真实数据撰写分任务结论、失败证据和改进排序 |

完整目录含答案，不能整体发给参与者。评分者先熟悉评分细则，学习者不提前接触后续题目或答案。原始记录、回答、截图和账号资料留在受控的仓库外目录，不提交Git。记录仅使用匿名代号，不填姓名、邮箱、Token或密钥。

## 执行顺序

1. 固定本轮Git提交、模型请求名称、返回名称和提示版本；使用独立试用账号与新会话，保持学习档案设置一致，不复用已有会话或学习记忆。Java先验经验记录为none/basic/experienced；取得参与者同意后填写consentedAt。真实模型调用须使用明确的新预算，之前59/59的循序学习额度已经用完，本轮工具本身不调用模型。
2. 按参与者编号交替主题顺序：P01先ArrayList，P02先HashMap，依次交替。这个安排只平衡操作顺序，不是随机分配处理条件。每个主题先完成前测，限时5分钟，不看教材、不运行代码、不问AI。
3. 在资料库上传当前主题的source文件，新建绑定该单份资料的RAG会话，使用普通教材问答完成task文件中的任务，限时15分钟；观察者记录求助、引用和反馈。首轮不使用尚未准入的自动循序题库，不将离线测验导入线上题库。教材讲解和生成反馈出现错误时保留失败证据，暂停该任务并说明，不能让错误继续充当教学依据。
4. 学习结束后30分钟内完成immediate题，限时5分钟，无资料、无提示、无执行代码。两主题均完成前，不能发放答案或纠正测验题；教师交互中的任务检查与这份独立测验分别记录。
5. 每个主题结束48–72小时后完成对应delayed题，同样独立、限时5分钟。先完成延后测验，再开展系统复习或展示答案；期间额外学习、看到答案或使用其他工具均须记录。无法按时参加就保留缺测，不能提前测完并伪造时间。
6. 评分者按每题两个独立要点评0/1/2分，保留原回答、评分理由和评分者；计分点互相独立，一个正确输出加一个正确解释可得2分。对非预期但成立的回答，标记复核，不能只按字符串机械判错。前后各套卷难度尚未经校准，分数差仅作试点描述。
7. 完成后运行summarize；结合原始证据撰写findings，区分任务、学习者与时间点。按影响程度、涉及学习者数和是否阻断任务排序问题，将内容质量问题回填阶段二，证据或状态问题回填阶段三。

如参与者先完成两主题前测，再学习两主题，也须记录实际时间并对所有人一致。测验阶段的 externalHelp 填false/true；无法确认则保留null，工具不当作独立测验。interveningStudy 为none/yes/unknown；额外学习不删除原始回答，但不纳入预定的无额外学习延后指标。

## 记录口径

`records.json` 的participants只纳入实际安排的匿名参与者；`participant-template.json`定义单人结构。未观察到的值保持null；明确观察为零才填写0。时间使用带时区的ISO格式，如`2026-10-21T15:00:00+08:00`。

| 字段 | 定义 |
| --- | --- |
| environment | appCommit为实际运行提交；modelRequested为请求名，modelReported为服务返回名；应用未暴露返回名时如实填not_exposed_by_application，不用请求名冒充；promptVersion至少记录提示源码提交 |
| startedAt/endedAt | 当前主题学习任务开始与结束，不包含前后独立测验 |
| conversationId/evidenceRefs | 线上会话ID与仓库外回答、截图、评分记录的相对引用；不能含认证信息 |
| taskCompleted | 观察者确认解释、自己的例子和任务检查均完成才为true；失败或卡住为false；未观察为null。它不是“已掌握” |
| helpCount | 主持人或系统给出额外提示的次数；初始任务说明不计入；重复同一提示的一次展示计1次；缺日志不补0 |
| citationsChecked/citationErrors | 人工逐项检查的引用片段数与错误数；不相关、失效或不支持对应主张算错误。同一片段在不同回答再次出现是新的检查项 |
| feedbackChecked/feedbackErrors | 人工检查的逐条反馈单元数与错误数；一条助手反馈消息算1单元，含多个错误仍计1个错误单元。未检查不等同于无错误 |
| phases.* | completedAt是本次作答结束；answers保存各题原回答、0/1/2或null分数、理由，scoredBy填写匿名评分者。模型或系统掌握分不替代评分 |
| issues | severity=high/medium/low，kind=citation/feedback/blocking/other；description说明具体触发、实际行为和影响，evidenceRef关联原始材料 |

high指错误教学结论、错误评分、无法继续任务或数据丢失；medium指需要额外干预才能继续；low指不影响核心任务的操作阻力。保留所有失败任务，不因得分低或中断将学习者从样本中删除。

本轮先人工记录，不新增线上研究数据接口或埋点。`GET /api/evaluation/overview`只是当前账号的运行统计；会话完成率、系统掌握分和聊天时长都不能代替独立学习测验。阶段三复习任务间隔一天是产品规则，与本试点48–72小时独立延后测验不同。

## 汇总与解释

```powershell
node backend/scripts/evaluate-stage4.mjs summarize `
  --input "$env:TEMP/ai-tutor-stage4-pilot-kit/records.json" `
  --output-file "$env:TEMP/ai-tutor-stage4-pilot-kit/report-after-collection.json"
```

汇总拒绝材料指纹改变、重复参与者／题目、无回答却打分、无评分理由、时间顺序错误、未取得同意就采集，以及错误数超过检查数。它不会自动识别语义正确性或人工造假。

每题2分，主题分转换为百分数；整人指标要求该轮两个主题均完成且符合时间和独立作答要求，取两个主题百分分的平均值。`learning`按学习者汇总，`topicLearning`分别描述主题；不能将同一学习者的12题或两主题当作12或2个独立样本。改善量使用同一学习者完整配对的前后分差，单位为百分点；缺测、外部帮助、超窗或额外学习不补零，报告各自分母及排除原因，保留原记录以供偏差分析。操作错误率的分母是实际检查项，不是消息总数或参与者数。

状态含not_collected、in_progress、ready_for_review、dry_run和self_test。ready_for_review只表示人工审阅已记载且至少4名学习者完成规定记录，可撰写人工结论；它不代表阶段四通过或教学效果成立。部分失败或失访也可形成有依据的负面试点结论，应说明缺测和停止原因，而不是为达到ready状态补造数据。

self_test用于本次用户个人走查，初始化一份P01空白记录，作答和时间不预填。观察到真实作答后realParticipants可为1，但pilotParticipants始终为0，状态始终为self_test；个人走查结果不进入初学者试点准备完成判定，不声称初学者教学效果。首次仅发放前测，实际作答后再进入学习和即时测验，延后测验须等待真实时间经过。

合成演练须使用`init --mode dry_run`，结果始终标记dry_run、realParticipants=0。所有报告的learningEffectEstablished均为false：单组试点没有同期对照、样本小、卷型未校准，不能因前后提高就声称教学因果效果。下一轮如比较普通聊天，须先固定同教材、模型和时间预算，以独立学习者随机分组；学习不能洗脱，不能让同一人先用一种方法学同一内容再换方法来宣称公平对照。对照方案、样本量和新调用预算在实际执行前另行确定。
