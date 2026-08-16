package me.zssu.ime.style

/**
 * Accuracy-first Japanese style transformer.
 *
 * Unlike a suffix cascade, this engine never guesses that every `〜ます` stem is an ichidan verb.
 * It performs longest-match phrase normalization, dictionary-backed conjugation in one pass, and
 * sentence-level tone adjustment. Formal honorifics are selected only when the subject supplies
 * enough evidence to distinguish respectful language from humble language.
 */
internal object HighAccuracyTextStyleTransformer {

    private data class Verb(
        val plain: String,
        val stem: String,
        val negativeStem: String,
        val past: String,
    ) {
        val negative = "${negativeStem}ない"
        val negativePast = "${negativeStem}なかった"
        val polite = "${stem}ます"
        val politePast = "${stem}ました"
        val politeNegative = "${stem}ません"
        val politeNegativePast = "${stem}ませんでした"
    }

    private class LiteralRewriter(
        replacements: Map<String, String>,
        requirePredicateBoundary: Boolean = false,
    ) {
        private val values = replacements
        private val pattern = if (replacements.isEmpty()) {
            null
        } else {
            val alternatives = replacements.keys
                .sortedByDescending(String::length)
                .joinToString("|") { Regex.escape(it) }
            val boundary = if (requirePredicateBoundary) PREDICATE_LOOKAHEAD else ""
            Regex("(?:$alternatives)$boundary")
        }

        fun rewrite(text: String): String =
            pattern?.replace(text) { values[it.value] ?: it.value } ?: text
    }

    fun apply(text: String, style: TextStyle): String {
        if (style == TextStyle.ORIGINAL || text.isBlank()) return text
        val canonical = normalize(text)
        return when (style) {
            TextStyle.ORIGINAL -> text
            TextStyle.PLAIN -> canonical
            TextStyle.CASUAL -> toCasual(canonical)
            TextStyle.NORMAL_KEIGO -> toNormalPolite(canonical)
            TextStyle.HARD_KEIGO -> toFormalKeigo(canonical)
            TextStyle.SOFT_KEIGO -> toSoftPolite(canonical)
        }
    }

    /**
     * Converts known expressions and inflections to a stable plain form. All literal inflections
     * are replaced by one regex pass, so a generated result cannot accidentally trigger a second
     * unrelated rule.
     */
    private fun normalize(text: String): String {
        var result = canonicalPhraseRewriter.rewrite(text)
        result = politeVerbToPlain.rewrite(result)
        result = adjectiveToPlain.rewrite(result)

        result = result
            .replace(Regex("([てで])おりませんでした$PREDICATE_LOOKAHEAD"), "$1いなかった")
            .replace(Regex("([てで])おりません$PREDICATE_LOOKAHEAD"), "$1いない")
            .replace(Regex("([てで])おりました$PREDICATE_LOOKAHEAD"), "$1いた")
            .replace(Regex("([てで])おります$PREDICATE_LOOKAHEAD"), "$1いる")
            .replace(Regex("([てで])いませんでした$PREDICATE_LOOKAHEAD"), "$1いなかった")
            .replace(Regex("([てで])いません$PREDICATE_LOOKAHEAD"), "$1いない")
            .replace(Regex("([てで])いました$PREDICATE_LOOKAHEAD"), "$1いた")
            .replace(Regex("([てで])います$PREDICATE_LOOKAHEAD"), "$1いる")
            .replace(Regex("ありませんでした$PREDICATE_LOOKAHEAD"), "なかった")
            .replace(Regex("ありません$PREDICATE_LOOKAHEAD"), "ない")
            .replace(Regex("ありました$PREDICATE_LOOKAHEAD"), "あった")
            .replace(Regex("あります$PREDICATE_LOOKAHEAD"), "ある")
            .replace(Regex("でございませんでした$PREDICATE_LOOKAHEAD"), "ではなかった")
            .replace(Regex("でございません$PREDICATE_LOOKAHEAD"), "ではない")
            .replace(Regex("ではありませんでした$PREDICATE_LOOKAHEAD"), "ではなかった")
            .replace(Regex("ではありません$PREDICATE_LOOKAHEAD"), "ではない")
            .replace(Regex("じゃありませんでした$PREDICATE_LOOKAHEAD"), "じゃなかった")
            .replace(Regex("じゃありません$PREDICATE_LOOKAHEAD"), "じゃない")
            .replace(Regex("でございました$PREDICATE_LOOKAHEAD"), "だった")
            .replace(Regex("でございます$PREDICATE_LOOKAHEAD"), "だ")
            .replace(Regex("でした$PREDICATE_LOOKAHEAD"), "だった")
            .replace(Regex("ですか(?=[？?])"), "か")
            .replace(Regex("です$PREDICATE_LOOKAHEAD"), "だ")
            .replace(Regex("でしょう$PREDICATE_LOOKAHEAD"), "だろう")
            .replace(Regex("てください$PREDICATE_LOOKAHEAD"), "てくれ")
            .replace(Regex("でください$PREDICATE_LOOKAHEAD"), "でくれ")

        return normalizeCasualContractions(result)
    }

    private fun normalizeCasualContractions(text: String): String = text
        .replace(Regex("ちゃった$PREDICATE_LOOKAHEAD"), "てしまった")
        .replace(Regex("じゃった$PREDICATE_LOOKAHEAD"), "でしまった")
        .replace(Regex("ちゃう$PREDICATE_LOOKAHEAD"), "てしまう")
        .replace(Regex("じゃう$PREDICATE_LOOKAHEAD"), "でしまう")
        .replace(Regex("なきゃ$PREDICATE_LOOKAHEAD"), "なければ")
        .replace(Regex("なくちゃ$PREDICATE_LOOKAHEAD"), "なくては")
        .replace(Regex("てる$PREDICATE_LOOKAHEAD"), "ている")
        .replace(Regex("てた$PREDICATE_LOOKAHEAD"), "ていた")

    private fun toNormalPolite(plain: String): String {
        var result = targetPhraseRewriters.getValue(TextStyle.NORMAL_KEIGO).rewrite(plain)
        result = plainVerbToPolite.rewrite(result)
        result = adjectiveToPolite.rewrite(result)
        result = result
            .replace(Regex("([てで])いなかった$PREDICATE_LOOKAHEAD"), "$1いませんでした")
            .replace(Regex("([てで])いない$PREDICATE_LOOKAHEAD"), "$1いません")
            .replace(Regex("([てで])いた$PREDICATE_LOOKAHEAD"), "$1いました")
            .replace(Regex("([てで])いる$PREDICATE_LOOKAHEAD"), "$1います")
            .replace(Regex("ではなかった$PREDICATE_LOOKAHEAD"), "ではありませんでした")
            .replace(Regex("ではない$PREDICATE_LOOKAHEAD"), "ではありません")
            .replace(Regex("じゃなかった$PREDICATE_LOOKAHEAD"), "ではありませんでした")
            .replace(Regex("じゃない$PREDICATE_LOOKAHEAD"), "ではありません")
            .replace(Regex("(?<=[はがも])なかった$PREDICATE_LOOKAHEAD"), "ありませんでした")
            .replace(Regex("(?<=[はがも])ない$PREDICATE_LOOKAHEAD"), "ありません")
            .replace(Regex("(?<=[はがも])あった$PREDICATE_LOOKAHEAD"), "ありました")
            .replace(Regex("(?<=[はがも])ある$PREDICATE_LOOKAHEAD"), "あります")
            .replace(Regex("だった$PREDICATE_LOOKAHEAD"), "でした")
            .replace(Regex("だろう$PREDICATE_LOOKAHEAD"), "でしょう")
            .replace(Regex("(?<!ん)だ$PREDICATE_LOOKAHEAD"), "です")
            .replace(Regex("てくれ$PREDICATE_LOOKAHEAD"), "てください")
            .replace(Regex("でくれ$PREDICATE_LOOKAHEAD"), "でください")
            .replace(Regex("(です|ます|ました|ません|ませんでした)(?=[？?])"), "$1か")
        return result
    }

    private fun toCasual(plain: String): String {
        var result = targetPhraseRewriters.getValue(TextStyle.CASUAL).rewrite(plain)
        result = result
            .replace("てしまう", "ちゃう")
            .replace("でしまう", "じゃう")
            .replace("てしまった", "ちゃった")
            .replace("でしまった", "じゃった")
            .replace("ている", "てる")
            .replace("でいる", "でる")
            .replace("ていた", "てた")
            .replace("でいた", "でた")
            .replace("ておく", "とく")
            .replace("なければ", "なきゃ")
            .replace("なくては", "なくちゃ")
            .replace('～', '〜')

        result = result
            .replace(Regex("([てで])る$STRICT_ENDING_LOOKAHEAD"), "$1るよ")
            .replace(Regex("([てで])た$STRICT_ENDING_LOOKAHEAD"), "$1たよ")
        return casualEndingRewriter.rewrite(result)
    }

    private fun toFormalKeigo(plain: String): String {
        var result = toNormalPolite(plain)
        result = formalCourtesyRewriter.rewrite(result)
        result = formalRequestRewriter.rewrite(result)
        result = rewriteSentences(result, ::formalizeSentence)
        return result
    }

    private fun formalizeSentence(sentence: String): String {
        var result = sentence
        result = when {
            FIRST_PERSON_SUBJECT.containsMatchIn(sentence) ->
                humbleRewriter.rewrite(result)
            RESPECTFUL_SUBJECT.containsMatchIn(sentence) ->
                respectfulRewriter.rewrite(result)
            else -> result
        }
        return result
            .replace(Regex("ではありませんでした$STRICT_ENDING_LOOKAHEAD"), "ではございませんでした")
            .replace(Regex("ではありません$STRICT_ENDING_LOOKAHEAD"), "ではございません")
            .replace(Regex("ありませんでした$STRICT_ENDING_LOOKAHEAD"), "ございませんでした")
            .replace(Regex("ありません$STRICT_ENDING_LOOKAHEAD"), "ございません")
            .replace(Regex("ありました$STRICT_ENDING_LOOKAHEAD"), "ございました")
            .replace(Regex("あります$STRICT_ENDING_LOOKAHEAD"), "ございます")
            .replace(Regex("でした$STRICT_ENDING_LOOKAHEAD"), "でございました")
            .replace(Regex("です$STRICT_ENDING_LOOKAHEAD"), "でございます")
    }

    private fun toSoftPolite(plain: String): String {
        var result = toNormalPolite(plain)
        result = softRequestRewriter.rewrite(result)
        return rewriteSentences(result, ::softenSentence)
    }

    private fun softenSentence(sentence: String): String {
        if (NON_DECORATED_SOFT_SENTENCE.containsMatchIn(sentence)) return sentence
        if ("と思いました" in sentence) {
            return sentence.replace(
                Regex("と思いました$STRICT_ENDING_LOOKAHEAD"),
                "かと思いました",
            )
        }
        if ("と思います" in sentence) {
            return sentence.replace(
                Regex("と思います$STRICT_ENDING_LOOKAHEAD"),
                "かと思います",
            )
        }
        return sentence
            .replace(Regex("ませんでした$STRICT_ENDING_LOOKAHEAD"), "ませんでしたね")
            .replace(Regex("ません$STRICT_ENDING_LOOKAHEAD"), "ませんね")
            .replace(Regex("ました$STRICT_ENDING_LOOKAHEAD"), "ましたね")
            .replace(Regex("でした$STRICT_ENDING_LOOKAHEAD"), "でしたね")
            .replace(Regex("ます$STRICT_ENDING_LOOKAHEAD"), "ますね")
            .replace(Regex("です$STRICT_ENDING_LOOKAHEAD"), "ですね")
    }

    private fun rewriteSentences(text: String, transform: (String) -> String): String {
        val result = StringBuilder(text.length + 16)
        var start = 0
        SENTENCE_TERMINATOR.findAll(text).forEach { match ->
            val end = match.range.last + 1
            result.append(transform(text.substring(start, end)))
            start = end
        }
        if (start < text.length) result.append(transform(text.substring(start)))
        return result.toString()
    }

    private val verbs = listOf(
        Verb("する", "し", "し", "した"),
        Verb("来る", "来", "来", "来た"),
        Verb("行く", "行き", "行か", "行った"),
        Verb("書く", "書き", "書か", "書いた"),
        Verb("聞く", "聞き", "聞か", "聞いた"),
        Verb("働く", "働き", "働か", "働いた"),
        Verb("開く", "開き", "開か", "開いた"),
        Verb("置く", "置き", "置か", "置いた"),
        Verb("動く", "動き", "動か", "動いた"),
        Verb("届く", "届き", "届か", "届いた"),
        Verb("読む", "読み", "読ま", "読んだ"),
        Verb("飲む", "飲み", "飲ま", "飲んだ"),
        Verb("頼む", "頼み", "頼ま", "頼んだ"),
        Verb("休む", "休み", "休ま", "休んだ"),
        Verb("住む", "住み", "住ま", "住んだ"),
        Verb("進む", "進み", "進ま", "進んだ"),
        Verb("話す", "話し", "話さ", "話した"),
        Verb("直す", "直し", "直さ", "直した"),
        Verb("探す", "探し", "探さ", "探した"),
        Verb("残す", "残し", "残さ", "残した"),
        Verb("返す", "返し", "返さ", "返した"),
        Verb("渡す", "渡し", "渡さ", "渡した"),
        Verb("待つ", "待ち", "待た", "待った"),
        Verb("持つ", "持ち", "持た", "持った"),
        Verb("立つ", "立ち", "立た", "立った"),
        Verb("思う", "思い", "思わ", "思った"),
        Verb("言う", "言い", "言わ", "言った"),
        Verb("使う", "使い", "使わ", "使った"),
        Verb("買う", "買い", "買わ", "買った"),
        Verb("会う", "会い", "会わ", "会った"),
        Verb("もらう", "もらい", "もらわ", "もらった"),
        Verb("送る", "送り", "送ら", "送った"),
        Verb("帰る", "帰り", "帰ら", "帰った"),
        Verb("戻る", "戻り", "戻ら", "戻った"),
        Verb("減る", "減り", "減ら", "減った"),
        Verb("入る", "入り", "入ら", "入った"),
        Verb("分かる", "分かり", "分から", "分かった"),
        Verb("終わる", "終わり", "終わら", "終わった"),
        Verb("作る", "作り", "作ら", "作った"),
        Verb("取る", "取り", "取ら", "取った"),
        Verb("知る", "知り", "知ら", "知った"),
        Verb("売る", "売り", "売ら", "売った"),
        Verb("切る", "切り", "切ら", "切った"),
        Verb("乗る", "乗り", "乗ら", "乗った"),
        Verb("走る", "走り", "走ら", "走った"),
        Verb("選ぶ", "選び", "選ば", "選んだ"),
        Verb("呼ぶ", "呼び", "呼ば", "呼んだ"),
        Verb("学ぶ", "学び", "学ば", "学んだ"),
        Verb("遊ぶ", "遊び", "遊ば", "遊んだ"),
        Verb("急ぐ", "急ぎ", "急が", "急いだ"),
        Verb("見る", "見", "見", "見た"),
        Verb("食べる", "食べ", "食べ", "食べた"),
        Verb("始める", "始め", "始め", "始めた"),
        Verb("考える", "考え", "考え", "考えた"),
        Verb("決める", "決め", "決め", "決めた"),
        Verb("調べる", "調べ", "調べ", "調べた"),
        Verb("認める", "認め", "認め", "認めた"),
        Verb("求める", "求め", "求め", "求めた"),
        Verb("変える", "変え", "変え", "変えた"),
        Verb("続ける", "続け", "続け", "続けた"),
        Verb("答える", "答え", "答え", "答えた"),
        Verb("覚える", "覚え", "覚え", "覚えた"),
        Verb("忘れる", "忘れ", "忘れ", "忘れた"),
        Verb("見せる", "見せ", "見せ", "見せた"),
        Verb("任せる", "任せ", "任せ", "任せた"),
        Verb("委ねる", "委ね", "委ね", "委ねた"),
        Verb("伝える", "伝え", "伝え", "伝えた"),
        Verb("届ける", "届け", "届け", "届けた"),
        Verb("分ける", "分け", "分け", "分けた"),
        Verb("増える", "増え", "増え", "増えた"),
        Verb("受ける", "受け", "受け", "受けた"),
        Verb("付ける", "付け", "付け", "付けた"),
        Verb("開ける", "開け", "開け", "開けた"),
        Verb("閉める", "閉め", "閉め", "閉めた"),
        Verb("閉じる", "閉じ", "閉じ", "閉じた"),
        Verb("出かける", "出かけ", "出かけ", "出かけた"),
        Verb("寝る", "寝", "寝", "寝た"),
        Verb("起きる", "起き", "起き", "起きた"),
        Verb("できる", "でき", "でき", "できた"),
    )

    private val politeVerbToPlain = LiteralRewriter(
        buildMap {
            for (verb in verbs) {
                put(verb.politeNegativePast, verb.negativePast)
                put(verb.politeNegative, verb.negative)
                put(verb.politePast, verb.past)
                put(verb.polite, verb.plain)
                put("${verb.politeNegativePast}か", "${verb.negativePast}か")
                put("${verb.politeNegative}か", "${verb.negative}か")
                put("${verb.politePast}か", "${verb.past}か")
                put("${verb.polite}か", "${verb.plain}か")
            }
        },
        requirePredicateBoundary = true,
    )

    private val plainVerbToPolite = LiteralRewriter(
        buildMap {
            for (verb in verbs) {
                put(verb.negativePast, verb.politeNegativePast)
                put(verb.negative, verb.politeNegative)
                put(verb.past, verb.politePast)
                put(verb.plain, verb.polite)
                put("${verb.negativePast}か", "${verb.politeNegativePast}か")
                put("${verb.negative}か", "${verb.politeNegative}か")
                put("${verb.past}か", "${verb.politePast}か")
                put("${verb.plain}か", "${verb.polite}か")
            }
        },
        requirePredicateBoundary = true,
    )

    private val adjectiveForms = listOf(
        "高い", "低い", "良い", "よい", "悪い", "早い", "遅い", "難しい", "易しい",
        "嬉しい", "悲しい", "楽しい", "美味しい", "忙しい", "詳しい", "新しい",
        "古い", "多い", "少ない", "近い", "遠い", "長い", "短い", "大きい", "小さい",
        "強い", "弱い", "暑い", "寒い", "熱い", "冷たい", "面白い", "つまらない",
    )

    private val adjectiveToPlain = LiteralRewriter(
        buildMap {
            for (adjective in adjectiveForms) {
                put("${adjective}です", adjective)
                val base = adjective.removeSuffix("い")
                put("${base}かったです", "${base}かった")
                put("${base}くありません", "${base}くない")
                put("${base}くありませんでした", "${base}くなかった")
            }
        },
        requirePredicateBoundary = true,
    )

    private val adjectiveToPolite = LiteralRewriter(
        buildMap {
            for (adjective in adjectiveForms) {
                put(adjective, "${adjective}です")
                val base = adjective.removeSuffix("い")
                put("${base}かった", "${base}かったです")
                put("${base}くない", "${base}くありません")
                put("${base}くなかった", "${base}くありませんでした")
            }
        },
        requirePredicateBoundary = true,
    )

    private val canonicalPhraseRewriter = LiteralRewriter(
        linkedMapOf(
            "よろしくお願いいたします" to "よろしく",
            "よろしくお願いします" to "よろしく",
            "誠にありがとうございます" to "ありがとう",
            "ありがとうございました" to "ありがとう",
            "ありがとうございます" to "ありがとう",
            "大変申し訳ございません" to "すまない",
            "申し訳ございません" to "すまない",
            "申し訳ありません" to "すまない",
            "ご迷惑をおかけしました" to "迷惑をかけた",
            "お待たせいたしました" to "待たせた",
            "お待たせしました" to "待たせた",
            "かしこまりました" to "承知した",
            "承知いたしました" to "承知した",
            "承知しました" to "承知した",
            "お世話になっております" to "世話になっている",
            "お世話になりました" to "世話になった",
            "お世話になります" to "世話になる",
            "ご確認ください" to "確認してくれ",
            "ご連絡ください" to "連絡してくれ",
            "ご検討ください" to "検討してくれ",
            "ご了承ください" to "了承してくれ",
            "お待ちください" to "待ってくれ",
            "お越しください" to "来てくれ",
            "ご覧になりました" to "見た",
            "ご覧になります" to "見る",
            "ご覧になる" to "見る",
            "召し上がりました" to "食べた",
            "召し上がります" to "食べる",
            "召し上がる" to "食べる",
            "いらっしゃいました" to "来た",
            "いらっしゃいます" to "来る",
            "いらっしゃる" to "来る",
            "おっしゃいました" to "言った",
            "おっしゃいます" to "言う",
            "おっしゃる" to "言う",
            "拝見いたしました" to "見た",
            "拝見いたします" to "見る",
            "拝見しました" to "見た",
            "拝見します" to "見る",
            "拝見する" to "見る",
            "頂戴いたしました" to "もらった",
            "頂戴いたします" to "もらう",
            "伺いました" to "聞いた",
            "伺います" to "聞く",
            "伺う" to "聞く",
            "参りました" to "行った",
            "参ります" to "行く",
            "参る" to "行く",
            "申しました" to "言った",
            "申します" to "言う",
            "申す" to "言う",
            "存じ上げております" to "知っている",
            "存じ上げています" to "知っている",
            "存じません" to "知らない",
            "ご存じです" to "知っている",
            "いたしました" to "した",
            "いたします" to "する",
            "すみません" to "すまない",
            "ごめんなさい" to "ごめん",
            "お疲れ様でした" to "お疲れ",
            "お疲れ様です" to "お疲れ",
        )
    )

    private val targetPhraseRewriters = mapOf(
        TextStyle.NORMAL_KEIGO to LiteralRewriter(
            linkedMapOf(
                "ありがとう" to "ありがとうございます",
                "すまない" to "すみません",
                "ごめん" to "すみません",
                "よろしく" to "よろしくお願いします",
                "お疲れ" to "お疲れ様です",
            )
        ),
        TextStyle.CASUAL to LiteralRewriter(
            linkedMapOf(
                "すまない" to "ごめん",
                "よろしく" to "よろしくね",
                "お疲れ" to "お疲れ",
            )
        ),
    )

    private val formalRequestRewriter = LiteralRewriter(
        linkedMapOf(
            "確認してください" to "ご確認ください",
            "連絡してください" to "ご連絡ください",
            "検討してください" to "ご検討ください",
            "了承してください" to "ご了承ください",
            "利用してください" to "ご利用ください",
            "参照してください" to "ご参照ください",
            "入力してください" to "ご入力ください",
            "登録してください" to "ご登録ください",
            "送ってください" to "お送りください",
            "知らせてください" to "お知らせください",
            "教えてください" to "お知らせください",
            "待ってください" to "お待ちください",
            "来てください" to "お越しください",
            "見てください" to "ご覧ください",
            "あれば" to "ございましたら",
            "よければ" to "よろしければ",
        )
    )

    private val formalCourtesyRewriter = LiteralRewriter(
        linkedMapOf(
            "ありがとうございます" to "誠にありがとうございます",
            "すみません" to "申し訳ございません",
            "よろしくお願いします" to "何卒よろしくお願いいたします",
        )
    )

    private val softRequestRewriter = LiteralRewriter(
        linkedMapOf(
            "確認してください" to "確認していただけると助かります",
            "連絡してください" to "連絡していただけると助かります",
            "検討してください" to "検討していただけると助かります",
            "送ってください" to "送っていただけると助かります",
            "知らせてください" to "知らせていただけると助かります",
            "教えてください" to "教えていただけると助かります",
        )
    )

    private val humbleRewriter = LiteralRewriter(
        linkedMapOf(
            "見ました" to "拝見いたしました",
            "見ます" to "拝見いたします",
            "行きました" to "参りました",
            "行きます" to "参ります",
            "来ました" to "参りました",
            "来ます" to "参ります",
            "言いました" to "申しました",
            "言います" to "申します",
            "聞きました" to "伺いました",
            "聞きます" to "伺います",
            "もらいました" to "頂戴いたしました",
            "もらいます" to "頂戴いたします",
            "確認しました" to "確認いたしました",
            "確認します" to "確認いたします",
        ),
        requirePredicateBoundary = true,
    )

    private val respectfulRewriter = LiteralRewriter(
        linkedMapOf(
            "見ました" to "ご覧になりました",
            "見ます" to "ご覧になります",
            "来ました" to "いらっしゃいました",
            "来ます" to "いらっしゃいます",
            "行きました" to "いらっしゃいました",
            "行きます" to "いらっしゃいます",
            "言いました" to "おっしゃいました",
            "言います" to "おっしゃいます",
            "食べました" to "召し上がりました",
            "食べます" to "召し上がります",
            "知っています" to "ご存じです",
        ),
        requirePredicateBoundary = true,
    )

    private val casualEndingRewriter by lazy {
        val endings = buildMap {
            put("だろう", "だろうね")
            put("だ", "だよ")
            put("だった", "だったよ")
            put("ではない", "ではないよ")
            put("ではなかった", "ではなかったよ")
            put("てくれ", "てくれよ")
            put("でくれ", "でくれよ")
            for (adjective in adjectiveForms) {
                put(adjective, "${adjective}よ")
                val base = adjective.removeSuffix("い")
                put("${base}かった", "${base}かったよ")
                put("${base}くない", "${base}くないよ")
                put("${base}くなかった", "${base}くなかったよ")
            }
            for (verb in verbs) {
                put(verb.plain, "${verb.plain}よ")
                put(verb.past, "${verb.past}よ")
                put(verb.negative, "${verb.negative}よ")
                put(verb.negativePast, "${verb.negativePast}よ")
            }
        }
        LiteralRewriter(endings, requirePredicateBoundary = true)
    }

    private const val PREDICATE_LOOKAHEAD =
        "(?=\\z|[。！？!?、,\\n\\r\\t ]|(?:が|けれど|けど|ので|から|のに|なら|し|とき|時|ため|まま|ものの|ところ))"
    private const val STRICT_ENDING_LOOKAHEAD = "(?=[。！!\\n\\r]|\\z)"
    private val SENTENCE_TERMINATOR = Regex("[。！？!?\\n]")
    private val FIRST_PERSON_SUBJECT =
        Regex("(?:私|わたし|僕|弊社|当社|当方|こちら)(?:は|が|も)")
    private val RESPECTFUL_SUBJECT =
        Regex("(?:お客様|皆様|先生|社長|部長|課長|会長|[\\p{L}一-龯々]{1,12}様)(?:は|が|も)")
    private val NON_DECORATED_SOFT_SENTENCE =
        Regex("(?:ありがとう|すみません|申し訳|お願いいたします|お願いします|ください|助かります|[？?])")
}
