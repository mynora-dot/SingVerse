# 内置助手形象

使用内置 imagegen 工具生成，来自本次用户选定的候选 05、31–42；运行时不调用生成服务。PNG 为 512 × 512 RGBA，保留透明通道，随 Vite 打包。原始高分辨率生成结果不作为运行时依赖。

生成提示词（逐款使用所选角色编号和对应候选图）：

> Extract and faithfully recreate ONLY character numbered {number} from this reference sheet as ONE standalone production music app companion. Preserve exact face, hairstyle, colors, pose, outfit, accessories, proportions and rendering style of selected character. Remove ALL other characters, labels, numbers, white background and ground shadows. Actual transparent alpha background. Complete full body and all accessories visible, centered on square canvas with 7 percent margins. No redesign, no text. Clean edges on light and dark UI.

文件名与持久化 ID 在 `../../pets.ts` 对应；增加形象时同步后端允许列表、OpenAPI 枚举与保存/读取行为测试。`*-idle.png` 是 512 × 512 的透明 2×2 四帧图集，每帧 256 × 256；以 CSS 离散切帧播放待机眨眼、手部及配件动作。选择页保留静态图，系统减少动态效果时回退静态图。属于四帧循环动画，非骨骼动画。


动作图集使用内置 imagegen 工具，以上述静态素材为参考逐款生成。提示词：

> Create a production FOUR FRAME ANIMATION SPRITE SHEET of this EXACT reference character. Square canvas divided into precisely TWO equal columns and TWO equal rows, FOUR equal square cells, NO gutters, no lines, no labels. Transparent alpha background. Each cell contains the same complete full-body character at EXACTLY MATCHED SCALE and ALIGNMENT. Preserve original identity, costume, palette, accessories and rendering style. Four consecutive subtle movement keyframes: neutral eyes open, anticipation eyes half closed, motion peak eyes closed, eyes reopening and returning toward neutral. Real movement of face and limbs, not translating an unchanged image. No text, no ground shadow, no background.

动作分别为熊猫拥抱音符、歌姬举麦、DJ 点头、法师托起星球、剑士招手、仿生少女手势、乐师拉弓、机器人显示屏眨眼与波形变化、机甲屈伸手指、领航员手势与全息变化、AI 核心环体转动、飞行员摆手、外星旅者张开双手。
