package com.jmreader.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * 屏蔽规则：支持三类屏蔽。
 *
 * 1. 按 tag 屏蔽：含任意被屏蔽 tag 的漫画不显示。
 *    注意：禁漫列表接口不返回 tags，需异步调 /album 补全（见 BaseListViewModel.enrichTags）。
 *    匹配时做繁简归一化 + 小写，避免"純愛"vs"纯爱"不命中。
 *
 * 2. 按名称关键词屏蔽：漫画标题包含任意被屏蔽关键词即不显示。
 *    关键词匹配同样做繁简归一化 + 小写。用户可输入标题片段，如"NTR"、"同人誌"。
 *
 * 3. 按作者屏蔽：漫画作者精确匹配（归一化后）被屏蔽作者集合任一即不显示。
 *    作者列表接口可返回，无需补全，比 tag 屏蔽更精准、即时生效。
 */
private val Context.blockedTagsStore by preferencesDataStore(name = "blocked_tags")

class BlockedTagsStore(private val context: Context) {
    private val tagKey = stringSetPreferencesKey("tags")
    private val nameKey = stringSetPreferencesKey("names")
    private val authorKey = stringSetPreferencesKey("authors")

    /** 被屏蔽的 tag 集合。 */
    val tags: Flow<Set<String>> = context.blockedTagsStore.data.map { it[tagKey] ?: emptySet() }

    /** 被屏蔽的名称关键词集合。 */
    val names: Flow<Set<String>> = context.blockedTagsStore.data.map { it[nameKey] ?: emptySet() }

    /** 被屏蔽的作者集合。 */
    val authors: Flow<Set<String>> = context.blockedTagsStore.data.map { it[authorKey] ?: emptySet() }

    /** tags + names + authors 合并的实时集合，供 ViewModel 监听变化触发重过滤。 */
    val allRules: Flow<Triple<Set<String>, Set<String>, Set<String>>> =
        combine(tags, names, authors) { t, n, a -> Triple(t, n, a) }

    suspend fun addTag(tag: String) {
        val t = tag.trim()
        if (t.isEmpty()) return
        context.blockedTagsStore.edit { prefs ->
            prefs[tagKey] = (prefs[tagKey] ?: emptySet()) + t
        }
    }

    suspend fun removeTag(tag: String) {
        context.blockedTagsStore.edit { prefs ->
            prefs[tagKey] = (prefs[tagKey] ?: emptySet()) - tag
        }
    }

    suspend fun addName(name: String) {
        val n = name.trim()
        if (n.isEmpty()) return
        context.blockedTagsStore.edit { prefs ->
            prefs[nameKey] = (prefs[nameKey] ?: emptySet()) + n
        }
    }

    suspend fun removeName(name: String) {
        context.blockedTagsStore.edit { prefs ->
            prefs[nameKey] = (prefs[nameKey] ?: emptySet()) - name
        }
    }

    suspend fun addAuthor(author: String) {
        val a = author.trim()
        if (a.isEmpty()) return
        context.blockedTagsStore.edit { prefs ->
            prefs[authorKey] = (prefs[authorKey] ?: emptySet()) + a
        }
    }

    suspend fun removeAuthor(author: String) {
        context.blockedTagsStore.edit { prefs ->
            prefs[authorKey] = (prefs[authorKey] ?: emptySet()) - author
        }
    }

    /**
     * 判断漫画是否命中屏蔽规则（tag/名称/作者任一命中即屏蔽）。
     *
     * 性能优化：接收预归一化的 [NormalizedRules]，避免每次调用都对规则集合重复 normalize。
     * 仅对 comic 端数据（tags/name/author）做归一化，规则端的归一化由 [normalizeRules] 预计算一次。
     *
     * 大小写：normalize 已做 lowercase()，屏蔽 "ntr" 可命中 "NTR"/"Ntr"/"nTr" 等。
     *
     * @param comicTags 漫画的标签集合（可能来自列表项的 category 兜底，或异步补全的真实 tags）
     * @param comicName 漫画标题
     * @param comicAuthor 漫画作者（列表接口通常已返回）
     * @param rules 预归一化的屏蔽规则集合（由 [normalizeRules] 生成）
     */
    fun isBlocked(
        comicTags: Collection<String>,
        comicName: String,
        comicAuthor: String?,
        rules: NormalizedRules,
    ): Boolean {
        if (rules.isEmpty()) return false

        // tag 匹配：comic tag 归一化后与预归一化的 blocked tags 精确比对
        if (rules.tags.isNotEmpty() && comicTags.isNotEmpty()) {
            if (comicTags.any { normalize(it) in rules.tags }) return true
        }

        // 名称关键词匹配：标题归一化后包含任一预归一化关键词即屏蔽
        if (rules.names.isNotEmpty() && comicName.isNotBlank()) {
            val normName = normalize(comicName)
            if (rules.names.any { it in normName }) return true
        }

        // 作者匹配：归一化后与预归一化的 blocked authors 精确比对
        if (rules.authors.isNotEmpty() && !comicAuthor.isNullOrBlank()) {
            if (normalize(comicAuthor) in rules.authors) return true
        }
        return false
    }

    /**
     * 预归一化屏蔽规则集合。
     * 列表过滤前调用一次，生成 [NormalizedRules]，后续 [isBlocked] 直接复用，
     * 避免对每条漫画都重复 normalize 整个规则集合（列表越大、规则越多，节省越明显）。
     */
    fun normalizeRules(
        blockedTags: Set<String>,
        blockedNames: Set<String>,
        blockedAuthors: Set<String>,
    ): NormalizedRules = NormalizedRules(
        tags = blockedTags.mapTo(HashSet(blockedTags.size)) { normalize(it) },
        names = blockedNames.mapTo(HashSet(blockedNames.size)) { normalize(it) },
        authors = blockedAuthors.mapTo(HashSet(blockedAuthors.size)) { normalize(it) },
    )

    /**
     * 预归一化的屏蔽规则。规则端只 normalize 一次，[isBlocked] 仅 normalize comic 端数据。
     */
    data class NormalizedRules(
        val tags: Set<String>,
        val names: Set<String>,
        val authors: Set<String>,
    ) {
        fun isEmpty() = tags.isEmpty() && names.isEmpty() && authors.isEmpty()
    }

    /**
     * 归一化：去空白 + 转小写 + 繁体转简体。
     * 禁漫返回繁体（"純愛"），用户多输入简体（"纯爱"），必须统一才能命中。
     * 大小写：lowercase() 保证 "NTR" 与 "ntr" 归一化后一致。
     */
    private fun normalize(s: String): String {
        return CommonT2S.convert(s.trim().lowercase())
    }
}

/**
 * 内置繁→简映射表，覆盖禁漫常见 tag，避免依赖额外库。
 * 当 OpenCC 不可用时作为兜底。
 */
private object CommonT2S {
    private val map = mapOf(
        '純' to '纯', '愛' to '爱', '調' to '调', '教' to '教', '慾' to '欲',
        '網' to '网', '誌' to '志', '畫' to '画', '漢' to '汉', '華' to '华',
        '龍' to '龙', '鳳' to '凤', '體' to '体', '豐' to '丰', '豬' to '猪',
        '貓' to '猫', '狗' to '狗', '鳥' to '鸟', '魚' to '鱼', '蟲' to '虫',
        '蘿' to '萝', '莉' to '莉', '觸' to '触', '手' to '手', '異' to '异',
        '世' to '世', '界' to '界', '學' to '学', '園' to '园', '生' to '生',
        '師' to '师', '姐' to '姐', '妹' to '妹', '兄' to '兄', '弟' to '弟',
        '媽' to '妈', '爸' to '爸', '女' to '女', '男' to '男', '妻' to '妻',
        '夫' to '夫', '婚' to '婚', '結' to '结', '離' to '离', '場' to '场',
        '員' to '员', '官' to '官', '司' to '司', '警' to '警', '賊' to '贼',
        '盜' to '盗', '戰' to '战', '鬥' to '斗', '殺' to '杀', '死' to '死',
        '血' to '血', '淚' to '泪', '傷' to '伤', '痛' to '痛', '藥' to '药',
        '毒' to '毒', '酒' to '酒', '煙' to '烟', '車' to '车', '飛' to '飞',
        '機' to '机', '船' to '船', '島' to '岛', '國' to '国', '區' to '区',
        '點' to '点', '線' to '线', '麵' to '面', '飯' to '饭', '湯' to '汤',
        '茶' to '茶', '糖' to '糖', '甜' to '甜', '苦' to '苦', '辣' to '辣',
        '聲' to '声', '音' to '音', '樂' to '乐', '舞' to '舞', '戲' to '戏',
        '劇' to '剧', '電' to '电', '視' to '视', '書' to '书', '報' to '报',
        '紙' to '纸', '筆' to '笔', '畫' to '画', '圖' to '图', '藝' to '艺',
        '術' to '术', '建' to '建', '築' to '筑', '工' to '工', '廠' to '厂',
        '商' to '商', '業' to '业', '農' to '农', '林' to '林', '漁' to '渔',
        '礦' to '矿', '金' to '金', '銀' to '银', '銅' to '铜', '鐵' to '铁',
        '鋼' to '钢', '石' to '石', '玉' to '玉', '珠' to '珠', '寶' to '宝',
        '貝' to '贝', '錢' to '钱', '買' to '买', '賣' to '卖', '商' to '商',
        '店' to '店', '鋪' to '铺', '館' to '馆', '廳' to '厅', '房' to '房',
        '屋' to '屋', '樓' to '楼', '橋' to '桥', '路' to '路', '街' to '街',
        '燈' to '灯', '鈴' to '铃', '鐘' to '钟', '錶' to '表', '鏡' to '镜',
        '傘' to '伞', '帽' to '帽', '鞋' to '鞋', '襪' to '袜', '衣' to '衣',
        '褲' to '裤', '裙' to '裙', '領' to '领', '袖' to '袖', '袋' to '袋',
        '繩' to '绳', '線' to '线', '絲' to '丝', '綢' to '绸', '緞' to '缎',
        '劍' to '剑', '刀' to '刀', '槍' to '枪', '彈' to '弹', '炮' to '炮',
        '弓' to '弓', '箭' to '箭', '盾' to '盾', '盔' to '盔', '甲' to '甲',
        '陣' to '阵', '營' to '营', '隊' to '队', '軍' to '军', '兵' to '兵',
        '將' to '将', '帥' to '帅', '王' to '王', '后' to '后', '皇' to '皇',
        '帝' to '帝', '宮' to '宫', '殿' to '殿', '堂' to '堂', '廟' to '庙',
        '神' to '神', '仙' to '仙', '魔' to '魔', '鬼' to '鬼', '妖' to '妖',
        '精' to '精', '靈' to '灵', '魂' to '魂', '魄' to '魄', '命' to '命',
        '運' to '运', '氣' to '气', '風' to '风', '雲' to '云', '雷' to '雷',
        '電' to '电', '雨' to '雨', '雪' to '雪', '冰' to '冰', '霜' to '霜',
        '露' to '露', '霧' to '雾', '霞' to '霞', '虹' to '虹', '星' to '星',
        '月' to '月', '日' to '日', '光' to '光', '暗' to '暗', '影' to '影',
        '色' to '色', '聲' to '声', '味' to '味', '香' to '香', '臭' to '臭',
        '冷' to '冷', '熱' to '热', '溫' to '温', '涼' to '凉', '暖' to '暖',
        '寒' to '寒', '凍' to '冻', '冰' to '冰', '融' to '融', '化' to '化',
        '變' to '变', '態' to '态', '形' to '形', '狀' to '状', '樣' to '样',
        '式' to '式', '型' to '型', '類' to '类', '種' to '种', '別' to '别',
        '個' to '个', '們' to '们', '的' to '的', '了' to '了', '是' to '是',
        '在' to '在', '有' to '有', '無' to '无', '不' to '不', '非' to '非',
        '長' to '长', '短' to '短', '高' to '高', '低' to '低', '大' to '大',
        '小' to '小', '多' to '多', '少' to '少', '寬' to '宽', '窄' to '窄',
        '厚' to '厚', '薄' to '薄', '深' to '深', '淺' to '浅', '重' to '重',
        '輕' to '轻', '快' to '快', '慢' to '慢', '早' to '早', '遲' to '迟',
        '新' to '新', '舊' to '旧', '好' to '好', '壞' to '坏', '美' to '美',
        '醜' to '丑', '強' to '强', '弱' to '弱', '硬' to '硬', '軟' to '软',
        '乾' to '干', '濕' to '湿', '淨' to '净', '髒' to '脏', '空' to '空',
        '滿' to '满', '實' to '实', '虛' to '虚', '真' to '真', '假' to '假',
        '對' to '对', '錯' to '错', '能' to '能', '會' to '会', '可' to '可',
        '以' to '以', '和' to '与', '與' to '与', '及' to '及', '或' to '或',
        '但' to '但', '而' to '而', '且' to '且', '則' to '则', '若' to '若',
        '雖' to '虽', '然' to '然', '因' to '因', '果' to '果', '為' to '为',
        '被' to '被', '讓' to '让', '使' to '使', '給' to '给', '受' to '受',
        '從' to '从', '到' to '到', '向' to '向', '往' to '往', '朝' to '朝',
        '裡' to '里', '外' to '外', '上' to '上', '下' to '下', '左' to '左',
        '右' to '右', '前' to '前', '後' to '后', '中' to '中', '間' to '间',
        '邊' to '边', '旁' to '旁', '近' to '近', '遠' to '远', '此' to '此',
        '彼' to '彼', '某' to '某', '每' to '每', '各' to '各', '全' to '全',
        '部' to '部', '分' to '分', '半' to '半', '雙' to '双', '單' to '单',
        '兩' to '两', '幾' to '几', '數' to '数', '量' to '量', '度' to '度',
        '次' to '次', '回' to '回', '遍' to '遍', '趟' to '趟', '場' to '场',
        '件' to '件', '隻' to '只', '條' to '条', '塊' to '块', '張' to '张',
        '本' to '本', '冊' to '册', '卷' to '卷', '篇' to '篇', '章' to '章',
        '節' to '节', '頁' to '页', '行' to '行', '列' to '列', '組' to '组',
        '群' to '群', '堆' to '堆', '排' to '排', '連' to '连', '續' to '续',
        '斷' to '断', '停' to '停', '止' to '止', '動' to '动', '靜' to '静',
        '生' to '生', '死' to '死', '活' to '活', '存' to '存', '在' to '在',
        '來' to '来', '去' to '去', '進' to '进', '出' to '出', '入' to '入',
        '退' to '退', '回' to '回', '返' to '返', '達' to '达', '到' to '到',
        '過' to '过', '經' to '经', '歷' to '历', '驗' to '验', '試' to '试',
        '驗' to '验', '查' to '查', '看' to '看', '見' to '见', '觀' to '观',
        '察' to '察', '覺' to '觉', '知' to '知', '識' to '识', '認' to '认',
        '記' to '记', '憶' to '忆', '忘' to '忘', '想' to '想', '念' to '念',
        '思' to '思', '考' to '考', '慮' to '虑', '算' to '算', '計' to '计',
        '數' to '数', '量' to '量', '測' to '测', '評' to '评', '估' to '估',
        '論' to '论', '說' to '说', '話' to '话', '言' to '言', '語' to '语',
        '聲' to '声', '音' to '音', '唱' to '唱', '叫' to '叫', '喊' to '喊',
        '哭' to '哭', '笑' to '笑', '罵' to '骂', '讚' to '赞', '誇' to '夸',
        '責' to '责', '備' to '备', '罰' to '罚', '賞' to '赏', '獎' to '奖',
        '送' to '送', '給' to '给', '收' to '收', '取' to '取', '拿' to '拿',
        '放' to '放', '置' to '置', '設' to '设', '立' to '立', '建' to '建',
        '造' to '造', '作' to '作', '做' to '做', '幹' to '干', '辦' to '办',
        '理' to '理', '處' to '处', '決' to '决', '定' to '定', '選' to '选',
        '擇' to '择', '挑' to '挑', '選' to '选', '換' to '换', '改' to '改',
        '變' to '变', '化' to '化', '成' to '成', '為' to '为', '當' to '当',
        '作' to '作', '用' to '用', '使' to '使', '令' to '令', '讓' to '让',
        '請' to '请', '求' to '求', '要' to '要', '需' to '需', '須' to '须',
        '應' to '应', '該' to '该', '能' to '能', '可' to '可', '以' to '以',
        '得' to '得', '必' to '必', '須' to '须', '就' to '就', '還' to '还',
        '也' to '也', '都' to '都', '已' to '已', '曾' to '曾', '將' to '将',
        '正' to '正', '在' to '在', '常' to '常', '時' to '时', '間' to '间',
        '日' to '日', '月' to '月', '年' to '年', '天' to '天', '夜' to '夜',
        '晚' to '晚', '早' to '早', '晨' to '晨', '夕' to '夕', '春' to '春',
        '夏' to '夏', '秋' to '秋', '冬' to '冬', '東' to '东', '南' to '南',
        '西' to '西', '北' to '北', '中' to '中', '外' to '外', '內' to '内',
        '裡' to '里', '上' to '上', '下' to '下', '左' to '左', '右' to '右',
        '前' to '前', '後' to '后', '旁' to '旁', '邊' to '边', '處' to '处',
        '地' to '地', '方' to '方', '位' to '位', '置' to '置', '點' to '点',
        '線' to '线', '面' to '面', '體' to '体', '形' to '形', '狀' to '状',
        '色' to '色', '聲' to '声', '味' to '味', '香' to '香', '光' to '光',
        '影' to '影', '熱' to '热', '冷' to '冷', '力' to '力', '氣' to '气',
        '風' to '风', '水' to '水', '火' to '火', '土' to '土', '金' to '金',
        '木' to '木', '石' to '石', '山' to '山', '河' to '河', '海' to '海',
        '湖' to '湖', '江' to '江', '溪' to '溪', '泉' to '泉', '井' to '井',
        '池' to '池', '塘' to '塘', '沼' to '沼', '澤' to '泽', '漠' to '漠',
        '沙' to '沙', '塵' to '尘', '土' to '土', '泥' to '泥', '石' to '石',
        '岩' to '岩', '崖' to '崖', '谷' to '谷', '洞' to '洞', '穴' to '穴',
        '窟' to '窟', '窿' to '窿', '坑' to '坑', '溝' to '沟', '渠' to '渠',
        '壩' to '坝', '堤' to '堤', '岸' to '岸', '灘' to '滩', '島' to '岛',
        '嶼' to '屿', '礁' to '礁', '岬' to '岬', '灣' to '湾', '港' to '港',
        '埠' to '埠', '碼' to '码', '頭' to '头', '船' to '船', '艦' to '舰',
        '艇' to '艇', '舟' to '舟', '筏' to '筏', '帆' to '帆', '槳' to '桨',
        '舵' to '舵', '錨' to '锚', '網' to '网', '釣' to '钓', '魚' to '鱼',
        '蝦' to '虾', '蟹' to '蟹', '貝' to '贝', '藻' to '藻', '鯨' to '鲸',
        '鯊' to '鲨', '鰭' to '鳍', '鱗' to '鳞', '龜' to '龟', '蛇' to '蛇',
        '鱷' to '鳄', '蛙' to '蛙', '蟾' to '蟾', '蜍' to '蜍', '蠑' to '蝾',
        '螈' to '螈', '蜥' to '蜥', '蜴' to '蜴', '鱗' to '鳞', '爪' to '爪',
        '角' to '角', '毛' to '毛', '皮' to '皮', '骨' to '骨', '肉' to '肉',
        '血' to '血', '脈' to '脉', '筋' to '筋', '腦' to '脑', '心' to '心',
        '肝' to '肝', '肺' to '肺', '腸' to '肠', '胃' to '胃', '膽' to '胆',
        '腎' to '肾', '眼' to '眼', '耳' to '耳', '口' to '口', '鼻' to '鼻',
        '舌' to '舌', '牙' to '牙', '齒' to '齿', '唇' to '唇', '喉' to '喉',
        '頸' to '颈', '肩' to '肩', '胸' to '胸', '背' to '背', '腹' to '腹',
        '腰' to '腰', '臀' to '臀', '腿' to '腿', '膝' to '膝', '腳' to '脚',
        '足' to '足', '踝' to '踝', '腕' to '腕', '肘' to '肘', '臂' to '臂',
        '掌' to '掌', '指' to '指', '甲' to '甲', '髮' to '发', '鬚' to '须',
        '眉' to '眉', '睫' to '睫', '淚' to '泪', '汗' to '汗', '脂' to '脂',
        '肪' to '肪', '乳' to '乳', '奶' to '奶', '汁' to '汁', '液' to '液',
        '膿' to '脓', '涕' to '涕', '唾' to '唾', '沫' to '沫', '尿' to '尿',
        '糞' to '粪', '屁' to '屁', '精' to '精', '卵' to '卵', '胎' to '胎',
        '嬰' to '婴', '兒' to '儿', '童' to '童', '少' to '少', '年' to '年',
        '青' to '青', '壯' to '壮', '老' to '老', '叟' to '叟', '翁' to '翁',
        '嫗' to '妪', '婆' to '婆', '公' to '公', '爺' to '爷', '奶' to '奶',
        '爸' to '爸', '媽' to '妈', '爹' to '爹', '娘' to '娘', '父' to '父',
        '母' to '母', '伯' to '伯', '叔' to '叔', '姑' to '姑', '姨' to '姨',
        '舅' to '舅', '嬸' to '婶', '嫂' to '嫂', '姊' to '姊', '妹' to '妹',
        '兄' to '兄', '弟' to '弟', '侄' to '侄', '甥' to '甥', '孫' to '孙',
        '親' to '亲', '戚' to '戚', '友' to '友', '鄰' to '邻', '居' to '居',
        '鄉' to '乡', '村' to '村', '鎮' to '镇', '城' to '城', '市' to '市',
        '都' to '都', '京' to '京', '州' to '州', '省' to '省', '縣' to '县',
        '區' to '区', '里' to '里', '鄰' to '邻', '街' to '街', '路' to '路',
        '巷' to '巷', '弄' to '弄', '號' to '号', '樓' to '楼', '層' to '层',
        '室' to '室', '房' to '房', '屋' to '屋', '頂' to '顶', '底' to '底',
        '牆' to '墙', '壁' to '壁', '門' to '门', '窗' to '窗', '戶' to '户',
        '鎖' to '锁', '鑰' to '钥', '匙' to '匙', '梯' to '梯', '階' to '阶',
        '台' to '台', '壇' to '坛', '場' to '场', '院' to '院', '園' to '园',
        '林' to '林', '叢' to '丛', '樹' to '树', '枝' to '枝', '葉' to '叶',
        '根' to '根', '莖' to '茎', '花' to '花', '果' to '果', '實' to '实',
        '種' to '种', '籽' to '籽', '草' to '草', '莓' to '莓', '苔' to '苔',
        '蘚' to '藓', '藤' to '藤', '蘆' to '芦', '葦' to '苇', '竹' to '竹',
        '筍' to '笋', '稻' to '稻', '麥' to '麦', '粱' to '粱', '粟' to '粟',
        '豆' to '豆', '瓜' to '瓜', '蔓' to '蔓', '藤' to '藤', '朵' to '朵',
        '瓣' to '瓣', '蕊' to '蕊', '蜜' to '蜜', '蜂' to '蜂', '蝶' to '蝶',
        '蛾' to '蛾', '蚊' to '蚊', '蠅' to '蝇', '蟻' to '蚁', '蛛' to '蛛',
        '網' to '网', '蠶' to '蚕', '絲' to '丝', '繭' to '茧', '蛹' to '蛹',
        '蛾' to '蛾', '翅' to '翅', '膀' to '膀', '飛' to '飞', '翔' to '翔',
        '棲' to '栖', '息' to '息', '窩' to '窝', '巢' to '巢', '穴' to '穴',
        '洞' to '洞', '窟' to '窟', '籠' to '笼', '圈' to '圈', '欄' to '栏',
        '廄' to '厩', '棚' to '棚', '舍' to '舍', '倉' to '仓', '庫' to '库',
        '店' to '店', '鋪' to '铺', '館' to '館', '廳' to '厅', '堂' to '堂',
        '室' to '室', '房' to '房', '院' to '院', '宮' to '宫', '殿' to '殿',
        '堂' to '堂', '廟' to '庙', '寺' to '寺', '塔' to '塔', '碑' to '碑',
        '坊' to '坊', '柱' to '柱', '樑' to '梁', '棟' to '栋', '樁' to '桩',
        '板' to '板', '片' to '片', '塊' to '块', '條' to '条', '根' to '根',
        '枝' to '枝', '段' to '段', '截' to '截', '節' to '节', '片' to '片',
        '絲' to '丝', '縷' to '缕', '滴' to '滴', '粒' to '粒', '顆' to '颗',
        '枚' to '枚', '只' to '只', '個' to '个', '件' to '件', '雙' to '双',
        '對' to '对', '副' to '副', '套' to '套', '組' to '组', '串' to '串',
        '排' to '排', '列' to '列', '行' to '行', '隊' to '队', '群' to '群',
        '幫' to '帮', '夥' to '伙', '黨' to '党', '派' to '派', '會' to '会',
        '社' to '社', '團' to '团', '體' to '体', '局' to '局', '部' to '部',
        '處' to '处', '科' to '科', '室' to '室', '所' to '所', '院' to '院',
        '校' to '校', '學' to '学', '塾' to '塾', '師' to '师', '生' to '生',
        '徒' to '徒', '弟' to '弟', '子' to '子', '女' to '女', '孩' to '孩',
        '嬰' to '婴', '幼' to '幼', '童' to '童', '少' to '少', '青' to '青',
        '年' to '年', '成' to '成', '人' to '人', '老' to '老', '者' to '者',
        '員' to '员', '工' to '工', '匠' to '匠', '師' to '师', '傅' to '傅',
        '徒' to '徒', '弟' to '弟', '生' to '生', '客' to '客', '主' to '主',
        '顧' to '顾', '買' to '买', '賣' to '卖', '商' to '商', '販' to '贩',
        '賈' to '贾', '貿' to '贸', '易' to '易', '交' to '交', '換' to '换',
        '給' to '给', '收' to '收', '付' to '付', '還' to '还', '借' to '借',
        '貸' to '贷', '租' to '租', '稅' to '税', '款' to '款', '錢' to '钱',
        '幣' to '币', '銀' to '银', '金' to '金', '銅' to '铜', '鐵' to '铁',
        '財' to '财', '富' to '富', '貧' to '贫', '窮' to '穷', '貴' to '贵',
        '賤' to '贱', '價' to '价', '值' to '值', '貴' to '贵', '廉' to '廉',
        '貴' to '贵', '重' to '重', '輕' to '轻', '大' to '大', '小' to '小',
        '長' to '长', '短' to '短', '高' to '高', '低' to '低', '深' to '深',
        '淺' to '浅', '厚' to '薄', '薄' to '薄', '寬' to '宽', '窄' to '窄',
        '粗' to '粗', '細' to '细', '尖' to '尖', '鈍' to '钝', '銳' to '锐',
        '方' to '方', '圓' to '圆', '扁' to '扁', '平' to '平', '直' to '直',
        '曲' to '曲', '彎' to '弯', '折' to '折', '斷' to '断', '裂' to '裂',
        '碎' to '碎', '整' to '整', '全' to '全', '滿' to '满', '空' to '空',
        '實' to '实', '虛' to '虚', '硬' to '硬', '軟' to '软', '堅' to '坚',
        '固' to '固', '牢' to '牢', '穩' to '稳', '鬆' to '松', '緊' to '紧',
        '滑' to '滑', '澀' to '涩', '黏' to '黏', '膩' to '腻', '濕' to '湿',
        '乾' to '干', '燥' to '燥', '熱' to '热', '冷' to '冷', '溫' to '温',
        '涼' to '凉', '暖' to '暖', '寒' to '寒', '凍' to '冻', '冰' to '冰',
        '雪' to '雪', '霜' to '霜', '露' to '露', '雨' to '雨', '雲' to '云',
        '霧' to '雾', '霞' to '霞', '虹' to '虹', '風' to '风', '颱' to '台',
        '龍' to '龙', '捲' to '卷', '風' to '风', '暴' to '暴', '雷' to '雷',
        '電' to '电', '閃' to '闪', '霆' to '霆', '震' to '震', '動' to '动',
        '搖' to '摇', '晃' to '晃', '顫' to '颤', '抖' to '抖', '振' to '振',
        '蕩' to '荡', '漾' to '漾', '波' to '波', '浪' to '浪', '濤' to '涛',
        '潮' to '潮', '汐' to '汐', '流' to '流', '淌' to '淌', '湧' to '涌',
        '沖' to '冲', '激' to '激', '沸' to '沸', '騰' to '腾', '蒸' to '蒸',
        '發' to '发', '氣' to '气', '煙' to '烟', '霧' to '雾', '塵' to '尘',
        '埃' to '埃', '灰' to '灰', '燼' to '烬', '炭' to '炭', '煤' to '煤',
        '燭' to '烛', '燈' to '灯', '光' to '光', '明' to '明', '亮' to '亮',
        '暗' to '暗', '黑' to '黑', '白' to '白', '紅' to '红', '黃' to '黄',
        '藍' to '蓝', '綠' to '绿', '紫' to '紫', '灰' to '灰', '褐' to '褐',
        '橙' to '橙', '粉' to '粉', '嫩' to '嫩', '豔' to '艳', '麗' to '丽',
        '美' to '美', '醜' to '丑', '俊' to '俊', '秀' to '秀', '麗' to '丽',
        '清' to '清', '秀' to '秀', '嬌' to '娇', '嫩' to '嫩', '柔' to '柔',
        '弱' to '弱', '強' to '强', '壯' to '壮', '健' to '健', '康' to '康',
        '病' to '病', '痛' to '痛', '傷' to '伤', '殘' to '残', '廢' to '废',
        '瞎' to '瞎', '聾' to '聋', '啞' to '哑', '瘸' to '瘸', '跛' to '跛',
        '駝' to '驼', '背' to '背', '彎' to '弯', '腰' to '腰', '駝' to '驼',
    )

    fun convert(s: String): String = buildString(s.length) {
        for (c in s) append(map[c] ?: c)
    }
}
