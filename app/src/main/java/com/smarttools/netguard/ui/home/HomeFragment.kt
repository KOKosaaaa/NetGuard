package com.smarttools.netguard.ui.home

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.smarttools.netguard.App
import com.smarttools.netguard.MainActivity
import com.smarttools.netguard.R
import com.smarttools.netguard.databinding.FragmentHomeBinding
import com.smarttools.netguard.model.ConnectionState
import com.smarttools.netguard.model.TrafficStatsMode
import com.smarttools.netguard.util.GeoLookup
import com.smarttools.netguard.util.TrafficFormatter
import com.smarttools.netguard.util.SpeedTester
import com.smarttools.netguard.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()

    private var pulseAnimator: ObjectAnimator? = null
    private var timerRunnable: Runnable? = null
    private var cursorBlinkRunnable: Runnable? = null
    private val cursorHandler = android.os.Handler(android.os.Looper.getMainLooper())

    // Easter-egg state: 5 quick taps on the profile name pop a Mr.Robot
    // quote toast. Counter resets after ~2s of inactivity so accidental
    // double-taps don't drift toward a trigger over time.
    private var profileTapCount = 0
    private var profileTapResetRunnable: Runnable? = null
    // Base prompt without cursor; cursor blink runnable composes it with
    // a blinking █/space each tick so the line width stays stable.
    private var fsocPromptBase: String = "root@fsociety:~$ awaiting target"
    private var fsocCursorOn = true
    // Mr.Robot quotes. en is canonical; tr maps Locale.getDefault().language
    // to a localized rendition. Display = `${en}\n— ${tr[lang]}` if a
    // translation exists; pure English line otherwise (covers en + any
    // locale we haven't translated yet so the line never goes blank).
    // Locale codes follow Android resource conventions: "in" for Indonesian
    // (legacy), "zh" for Simplified Chinese, lowercase 2-letter ISO 639-1.
    private data class FsocQuote(val en: String, val tr: Map<String, String>)
    private val fsocQuotes = listOf(
        FsocQuote("hello, friend.", mapOf(
            "ru" to "привет, друг.",
            "de" to "hallo, freund.",
            "es" to "hola, amigo.",
            "fr" to "bonjour, ami.",
            "it" to "ciao, amico.",
            "pt" to "olá, amigo.",
            "ja" to "やあ、友よ。",
            "ko" to "안녕, 친구.",
            "zh" to "你好，朋友。",
            "ar" to "مرحباً، يا صديق.",
            "hi" to "नमस्ते, दोस्त।",
            "th" to "สวัสดี เพื่อน",
            "tr" to "merhaba, dostum.",
            "vi" to "chào, bạn của tôi.",
            "in" to "halo, kawan."
        )),
        FsocQuote("control is an illusion.", mapOf(
            "ru" to "контроль — иллюзия.",
            "de" to "kontrolle ist eine illusion.",
            "es" to "el control es una ilusión.",
            "fr" to "le contrôle est une illusion.",
            "it" to "il controllo è un'illusione.",
            "pt" to "o controle é uma ilusão.",
            "ja" to "支配は幻想だ。",
            "ko" to "통제는 환상이다.",
            "zh" to "控制是一种幻觉。",
            "ar" to "السيطرة وهم.",
            "hi" to "नियंत्रण एक भ्रम है।",
            "th" to "การควบคุมคือภาพลวงตา",
            "tr" to "kontrol bir illüzyondur.",
            "vi" to "kiểm soát chỉ là ảo tưởng.",
            "in" to "kendali itu ilusi."
        )),
        FsocQuote("we are all alone, together.", mapOf(
            "ru" to "мы все одиноки, вместе.",
            "de" to "wir sind alle allein, zusammen.",
            "es" to "todos estamos solos, juntos.",
            "fr" to "nous sommes tous seuls, ensemble.",
            "it" to "siamo tutti soli, insieme.",
            "pt" to "estamos todos sozinhos, juntos.",
            "ja" to "私たちはみな、共に孤独だ。",
            "ko" to "우리는 모두 함께 외롭다.",
            "zh" to "我们都孤独，却在一起。",
            "ar" to "كلّنا وحيدون، معاً.",
            "hi" to "हम सब अकेले हैं, एक साथ।",
            "th" to "เราทุกคนเดียวดาย ด้วยกัน",
            "tr" to "hepimiz yalnızız, birlikte.",
            "vi" to "tất cả chúng ta đều cô đơn, cùng nhau.",
            "in" to "kita semua sendiri, bersama."
        )),
        FsocQuote(
            "every hacker has her fixation. you hack people, i hack time.",
            mapOf(
                "ru" to "у каждого хакера своя одержимость. ты хакаешь людей, я — время.",
                "de" to "jeder hacker hat seine fixierung. du hackst menschen, ich hacke zeit.",
                "es" to "cada hacker tiene su obsesión. tú hackeas a la gente, yo hackeo el tiempo.",
                "fr" to "chaque hacker a son obsession. tu pirates les gens, moi je pirate le temps.",
                "it" to "ogni hacker ha la sua ossessione. tu hackeri le persone, io hackero il tempo.",
                "pt" to "todo hacker tem sua obsessão. você hackeia pessoas, eu hackeio o tempo.",
                "ja" to "ハッカーには執着がある。お前は人を、俺は時間をハックする。",
                "ko" to "모든 해커에게는 집착이 있다. 너는 사람을, 나는 시간을 해킹한다.",
                "zh" to "每个黑客都有执念。你黑人，我黑时间。",
                "ar" to "لكلّ هاكر هوسه. أنت تخترق الناس، وأنا أخترق الزمن.",
                "hi" to "हर हैकर का अपना जुनून है। तुम लोगों को हैक करते हो, मैं समय को।",
                "th" to "แฮกเกอร์ทุกคนมีความหลงใหลของตน คุณแฮกผู้คน ฉันแฮกเวลา",
                "tr" to "her hacker'ın bir takıntısı vardır. sen insanları hack'lersin, ben zamanı.",
                "vi" to "mỗi hacker đều có nỗi ám ảnh riêng. bạn hack người, tôi hack thời gian.",
                "in" to "setiap hacker punya obsesinya. kamu hack orang, aku hack waktu."
            )
        ),
        FsocQuote("people always make the best exploits.", mapOf(
            "ru" to "люди — лучший эксплойт.",
            "de" to "menschen sind immer der beste exploit.",
            "es" to "la gente siempre es el mejor exploit.",
            "fr" to "les gens font toujours les meilleurs exploits.",
            "it" to "le persone sono sempre il miglior exploit.",
            "pt" to "as pessoas sempre são o melhor exploit.",
            "ja" to "人はいつだって最高のエクスプロイトだ。",
            "ko" to "사람은 언제나 최고의 익스플로잇이다.",
            "zh" to "人，永远是最好的漏洞。",
            "ar" to "البشر دائماً أفضل ثغرة.",
            "hi" to "लोग हमेशा सबसे अच्छे एक्सप्लॉइट होते हैं।",
            "th" to "คนคือช่องโหว่ที่ดีที่สุดเสมอ",
            "tr" to "insanlar her zaman en iyi exploit'tir.",
            "vi" to "con người luôn là exploit tốt nhất.",
            "in" to "manusia selalu exploit terbaik."
        )),
        FsocQuote("the world is a dangerous place.", mapOf(
            "ru" to "мир — опасное место.",
            "de" to "die welt ist ein gefährlicher ort.",
            "es" to "el mundo es un lugar peligroso.",
            "fr" to "le monde est un endroit dangereux.",
            "it" to "il mondo è un posto pericoloso.",
            "pt" to "o mundo é um lugar perigoso.",
            "ja" to "世界は危険な場所だ。",
            "ko" to "세상은 위험한 곳이다.",
            "zh" to "世界是个危险的地方。",
            "ar" to "العالم مكان خطر.",
            "hi" to "दुनिया एक खतरनाक जगह है।",
            "th" to "โลกใบนี้คือสถานที่อันตราย",
            "tr" to "dünya tehlikeli bir yer.",
            "vi" to "thế giới là nơi nguy hiểm.",
            "in" to "dunia adalah tempat yang berbahaya."
        )),
        FsocQuote(
            "give a man a gun, he can rob a bank. give a man a bank, he can rob the world.",
            mapOf(
                "ru" to "дай человеку пистолет — ограбит банк. дай человеку банк — ограбит мир.",
                "de" to "gib einem mann eine pistole, er beraubt eine bank. gib ihm eine bank, er beraubt die welt.",
                "es" to "dale a un hombre una pistola y robará un banco. dale un banco y robará al mundo.",
                "fr" to "donne un pistolet à un homme, il braque une banque. donne-lui une banque, il braque le monde.",
                "it" to "dai a un uomo una pistola, rapinerà una banca. dagli una banca, rapinerà il mondo.",
                "pt" to "dê a um homem uma arma, ele rouba um banco. dê-lhe um banco, ele rouba o mundo.",
                "ja" to "銃を渡せば銀行を奪う。銀行を渡せば世界を奪う。",
                "ko" to "총을 주면 은행을 털고, 은행을 주면 세상을 턴다.",
                "zh" to "给一个人枪，他能抢银行；给他一家银行，他能抢全世界。",
                "ar" to "أعطِ رجلاً مسدساً، يسطو على مصرف. أعطه مصرفاً، يسطو على العالم.",
                "hi" to "किसी को बंदूक दो, वह बैंक लूटेगा। उसे बैंक दो, वह दुनिया लूटेगा।",
                "th" to "ให้ปืนกับคนหนึ่ง เขาปล้นธนาคารได้ ให้ธนาคารกับเขา เขาปล้นโลกได้",
                "tr" to "bir adama silah ver, banka soyar. ona banka ver, dünyayı soyar.",
                "vi" to "đưa cho ai đó một khẩu súng, hắn sẽ cướp một ngân hàng. đưa hắn một ngân hàng, hắn sẽ cướp cả thế giới.",
                "in" to "beri seseorang pistol, dia akan merampok bank. beri dia bank, dia akan merampok dunia."
            )
        ),
        FsocQuote("our democracy has been hacked.", mapOf(
            "ru" to "нашу демократию взломали.",
            "de" to "unsere demokratie wurde gehackt.",
            "es" to "nuestra democracia ha sido hackeada.",
            "fr" to "notre démocratie a été piratée.",
            "it" to "la nostra democrazia è stata hackerata.",
            "pt" to "nossa democracia foi hackeada.",
            "ja" to "我々の民主主義はハックされた。",
            "ko" to "우리 민주주의는 해킹당했다.",
            "zh" to "我们的民主已被黑客攻击。",
            "ar" to "ديمقراطيتنا قد اختُرقت.",
            "hi" to "हमारे लोकतंत्र को हैक कर लिया गया है।",
            "th" to "ประชาธิปไตยของเราถูกแฮก",
            "tr" to "demokrasimiz hack'lendi.",
            "vi" to "nền dân chủ của chúng ta đã bị hack.",
            "in" to "demokrasi kita telah diretas."
        )),
        FsocQuote("fuck society.", mapOf(
            "ru" to "к чёрту общество.",
            "de" to "scheiß auf die gesellschaft.",
            "es" to "que se joda la sociedad.",
            "fr" to "merde à la société.",
            "it" to "fanculo la società.",
            "pt" to "foda-se a sociedade.",
            "ja" to "社会なんてくたばれ。",
            "ko" to "사회 따위 엿이나 먹어라.",
            "zh" to "去他妈的社会。",
            "ar" to "تباً للمجتمع.",
            "hi" to "समाज भाड़ में जाए।",
            "th" to "ช่างหัวสังคม",
            "tr" to "siktir et toplumu.",
            "vi" to "kệ cha xã hội.",
            "in" to "persetan dengan masyarakat."
        )),
        FsocQuote("power belongs to the people that take it.", mapOf(
            "ru" to "власть принадлежит тем, кто её берёт.",
            "de" to "macht gehört denen, die sie sich nehmen.",
            "es" to "el poder pertenece a quienes lo toman.",
            "fr" to "le pouvoir appartient à ceux qui le prennent.",
            "it" to "il potere appartiene a chi se lo prende.",
            "pt" to "o poder pertence a quem o toma.",
            "ja" to "力は、それを奪う者のものだ。",
            "ko" to "권력은 그것을 쥐는 자의 것이다.",
            "zh" to "权力属于敢于夺取它的人。",
            "ar" to "السلطة لمن يأخذها.",
            "hi" to "ताक़त उन्हीं की होती है जो उसे लेते हैं।",
            "th" to "อำนาจเป็นของผู้ที่ฉวยมันมา",
            "tr" to "iktidar, onu alanlara aittir.",
            "vi" to "quyền lực thuộc về kẻ giành lấy nó.",
            "in" to "kekuasaan milik mereka yang mengambilnya."
        )),
        FsocQuote("bonsoir, elliot.", mapOf(
            "ru" to "бонжур, эллиот.",
            "de" to "bonsoir, elliot.",
            "es" to "bonsoir, elliot.",
            "fr" to "bonsoir, elliot.",
            "it" to "bonsoir, elliot.",
            "pt" to "bonsoir, elliot.",
            "ja" to "ボンソワール、エリオット。",
            "ko" to "봉수아, 엘리엇.",
            "zh" to "晚上好，艾略特。",
            "ar" to "مساء الخير، إليوت.",
            "hi" to "बोंसुआ, इलियट।",
            "th" to "บงซัวร์ เอลเลียต",
            "tr" to "iyi akşamlar, elliot.",
            "vi" to "chào buổi tối, elliot.",
            "in" to "selamat malam, elliot."
        )),
        FsocQuote("we're all living in each other's paranoia.", mapOf(
            "ru" to "мы все живём в чужой паранойе.",
            "de" to "wir leben alle in der paranoia des anderen.",
            "es" to "todos vivimos en la paranoia del otro.",
            "fr" to "on vit tous dans la paranoïa des autres.",
            "it" to "viviamo tutti nella paranoia degli altri.",
            "pt" to "vivemos todos na paranoia uns dos outros.",
            "ja" to "我々は互いの偏執の中で生きている。",
            "ko" to "우리 모두 서로의 편집증 속에 살고 있다.",
            "zh" to "我们都活在彼此的偏执之中。",
            "ar" to "كلّنا نعيش في جنون ارتياب الآخرين.",
            "hi" to "हम सब एक-दूसरे के पैरानोया में जी रहे हैं।",
            "th" to "เราต่างใช้ชีวิตอยู่ในความหวาดระแวงของกันและกัน",
            "tr" to "hepimiz birbirimizin paranoyasında yaşıyoruz.",
            "vi" to "tất cả chúng ta đều sống trong nỗi hoang tưởng của nhau.",
            "in" to "kita semua hidup dalam paranoia satu sama lain."
        )),
        FsocQuote("there's a bug in everyone.", mapOf(
            "ru" to "в каждом есть баг.",
            "de" to "in jedem steckt ein bug.",
            "es" to "hay un bug en todos.",
            "fr" to "il y a un bug en chacun.",
            "it" to "in ognuno c'è un bug.",
            "pt" to "há um bug em cada um.",
            "ja" to "誰の中にもバグがある。",
            "ko" to "모두에게는 버그가 있다.",
            "zh" to "每个人心里都有一个 bug。",
            "ar" to "في كلّ منا خلل برمجي.",
            "hi" to "हर किसी में एक बग होता है।",
            "th" to "ในตัวทุกคนล้วนมีบั๊ก",
            "tr" to "herkesin içinde bir bug var.",
            "vi" to "trong mỗi chúng ta đều có một bug.",
            "in" to "ada bug di dalam diri setiap orang."
        )),
        FsocQuote("are you a one or a zero?", mapOf(
            "ru" to "ты единица или ноль?",
            "de" to "bist du eine eins oder eine null?",
            "es" to "¿eres un uno o un cero?",
            "fr" to "es-tu un un ou un zéro ?",
            "it" to "sei un uno o uno zero?",
            "pt" to "você é um um ou um zero?",
            "ja" to "お前は1か、それとも0か？",
            "ko" to "너는 1이냐, 0이냐?",
            "zh" to "你是 1 还是 0？",
            "ar" to "هل أنت واحد أم صفر؟",
            "hi" to "क्या तुम एक हो या शून्य?",
            "th" to "นายเป็น 1 หรือ 0 กันแน่?",
            "tr" to "sen bir misin, sıfır mı?",
            "vi" to "bạn là số 1 hay số 0?",
            "in" to "kamu satu atau nol?"
        )),
        FsocQuote("money. it's the bug.", mapOf(
            "ru" to "деньги. это и есть баг.",
            "de" to "geld. das ist der bug.",
            "es" to "el dinero. ese es el bug.",
            "fr" to "l'argent. c'est ça le bug.",
            "it" to "il denaro. è quello il bug.",
            "pt" to "dinheiro. esse é o bug.",
            "ja" to "金。これがバグだ。",
            "ko" to "돈. 그게 바로 버그다.",
            "zh" to "钱。这就是那个 bug。",
            "ar" to "المال. هذا هو الخلل.",
            "hi" to "पैसा। यही असली बग है।",
            "th" to "เงิน นั่นแหละคือบั๊ก",
            "tr" to "para. asıl bug bu.",
            "vi" to "tiền. đó chính là cái bug.",
            "in" to "uang. itulah bug-nya."
        )),
        FsocQuote("we have to wear masks to be true.", mapOf(
            "ru" to "чтобы быть собой, надо носить маску.",
            "de" to "wir müssen masken tragen, um echt zu sein.",
            "es" to "tenemos que llevar máscaras para ser auténticos.",
            "fr" to "il faut porter des masques pour être vrais.",
            "it" to "dobbiamo indossare maschere per essere veri.",
            "pt" to "temos que usar máscaras para sermos verdadeiros.",
            "ja" to "本当の自分でいるには、仮面が必要だ。",
            "ko" to "진실되려면 가면을 써야 한다.",
            "zh" to "戴上面具，才能做真正的自己。",
            "ar" to "علينا أن نرتدي الأقنعة لنكون صادقين.",
            "hi" to "सच होने के लिए हमें मुखौटे पहनने पड़ते हैं।",
            "th" to "เราต้องสวมหน้ากากเพื่อที่จะเป็นตัวจริง",
            "tr" to "gerçek olmak için maske takmamız gerek.",
            "vi" to "phải đeo mặt nạ thì mới là chính mình.",
            "in" to "kita harus memakai topeng untuk menjadi diri sendiri."
        )),
        FsocQuote("sometimes i dream of saving the world.", mapOf(
            "ru" to "иногда я мечтаю спасти мир.",
            "de" to "manchmal träume ich davon, die welt zu retten.",
            "es" to "a veces sueño con salvar el mundo.",
            "fr" to "parfois je rêve de sauver le monde.",
            "it" to "a volte sogno di salvare il mondo.",
            "pt" to "às vezes sonho em salvar o mundo.",
            "ja" to "ときどき、世界を救う夢を見るんだ。",
            "ko" to "가끔은 세상을 구하는 꿈을 꾼다.",
            "zh" to "我有时会梦见自己拯救世界。",
            "ar" to "أحياناً أحلم بإنقاذ العالم.",
            "hi" to "कभी-कभी मैं दुनिया को बचाने का सपना देखता हूँ।",
            "th" to "บางครั้งฉันก็ฝันถึงการกอบกู้โลก",
            "tr" to "bazen dünyayı kurtarmayı hayal ediyorum.",
            "vi" to "đôi khi tôi mơ về việc cứu thế giới.",
            "in" to "kadang aku bermimpi menyelamatkan dunia."
        )),
        FsocQuote("i wanted to save the world.", mapOf(
            "ru" to "я хотел спасти мир.",
            "de" to "ich wollte die welt retten.",
            "es" to "yo quería salvar el mundo.",
            "fr" to "je voulais sauver le monde.",
            "it" to "volevo salvare il mondo.",
            "pt" to "eu queria salvar o mundo.",
            "ja" to "俺は世界を救いたかった。",
            "ko" to "나는 세상을 구하고 싶었다.",
            "zh" to "我曾想拯救世界。",
            "ar" to "كنتُ أريد أن أنقذ العالم.",
            "hi" to "मैं दुनिया बचाना चाहता था।",
            "th" to "ฉันอยากกอบกู้โลก",
            "tr" to "dünyayı kurtarmak istemiştim.",
            "vi" to "tôi đã muốn cứu thế giới.",
            "in" to "aku ingin menyelamatkan dunia."
        )),
        FsocQuote(
            "everyone has secrets. it's how you choose to protect them that matters.",
            mapOf(
                "ru" to "у всех есть секреты. важно, как ты их защищаешь.",
                "de" to "jeder hat geheimnisse. wichtig ist, wie du sie schützt.",
                "es" to "todos tienen secretos. lo que importa es cómo los proteges.",
                "fr" to "tout le monde a des secrets. ce qui compte, c'est comment tu les protèges.",
                "it" to "tutti hanno segreti. l'importante è come scegli di proteggerli.",
                "pt" to "todos têm segredos. o que importa é como você os protege.",
                "ja" to "誰にでも秘密はある。大事なのは、それをどう守るかだ。",
                "ko" to "누구나 비밀이 있다. 중요한 건 그것을 어떻게 지키느냐다.",
                "zh" to "每个人都有秘密。重要的是你如何守护它们。",
                "ar" to "للجميع أسرار. المهم كيف تختار حمايتها.",
                "hi" to "हर किसी के राज़ होते हैं। मायने रखता है कि तुम उन्हें कैसे बचाते हो।",
                "th" to "ทุกคนต่างมีความลับ สำคัญที่ว่าจะปกป้องมันอย่างไร",
                "tr" to "herkesin sırrı vardır. önemli olan, onları nasıl koruduğundur.",
                "vi" to "ai cũng có bí mật. điều quan trọng là bạn chọn bảo vệ chúng thế nào.",
                "in" to "semua orang punya rahasia. yang penting bagaimana kamu melindunginya."
            )
        ),
        FsocQuote("i am mr. robot.", mapOf(
            "ru" to "я — мистер робот.",
            "de" to "ich bin mr. robot.",
            "es" to "yo soy mr. robot.",
            "fr" to "je suis mr. robot.",
            "it" to "io sono mr. robot.",
            "pt" to "eu sou mr. robot.",
            "ja" to "俺がミスターロボットだ。",
            "ko" to "내가 미스터 로봇이다.",
            "zh" to "我就是机器人先生。",
            "ar" to "أنا السيّد روبوت.",
            "hi" to "मैं मिस्टर रोबोट हूँ।",
            "th" to "ฉันคือมิสเตอร์โรบอต",
            "tr" to "ben mr. robot'um.",
            "vi" to "tôi là mr. robot.",
            "in" to "akulah mr. robot."
        )),
        FsocQuote(
            "even the most weak-willed of us has the capacity to change the world.",
            mapOf(
                "ru" to "даже самые слабые способны изменить мир.",
                "de" to "selbst die schwächsten unter uns können die welt verändern.",
                "es" to "incluso los más débiles de nosotros podemos cambiar el mundo.",
                "fr" to "même les plus faibles d'entre nous peuvent changer le monde.",
                "it" to "anche i più deboli tra noi possono cambiare il mondo.",
                "pt" to "até os mais fracos entre nós podem mudar o mundo.",
                "ja" to "もっとも弱き者にすら、世界を変える力がある。",
                "ko" to "가장 의지가 약한 자조차 세상을 바꿀 수 있다.",
                "zh" to "即便意志最弱者，也能改变世界。",
                "ar" to "حتى أضعفنا إرادةً قادر على تغيير العالم.",
                "hi" to "हममें से सबसे कमज़ोर भी दुनिया बदलने की ताक़त रखता है।",
                "th" to "แม้แต่ผู้ที่อ่อนแอที่สุดในพวกเรา ก็มีพลังที่จะเปลี่ยนโลก",
                "tr" to "en zayıf irademiz bile dünyayı değiştirebilir.",
                "vi" to "ngay cả kẻ yếu nhất trong chúng ta cũng có thể thay đổi thế giới.",
                "in" to "bahkan yang paling lemah pun mampu mengubah dunia."
            )
        ),
        FsocQuote(
            "sometimes the only way to win is to walk away.",
            mapOf(
                "ru" to "иногда единственный способ победить — уйти.",
                "de" to "manchmal ist die einzige art zu gewinnen, wegzugehen.",
                "es" to "a veces la única forma de ganar es marcharse.",
                "fr" to "parfois, la seule façon de gagner, c'est de partir.",
                "it" to "a volte l'unico modo per vincere è andarsene.",
                "pt" to "às vezes, a única forma de vencer é ir embora.",
                "ja" to "ときに、勝つ唯一の方法は立ち去ることだ。",
                "ko" to "때로는 이기는 유일한 방법은 떠나는 것이다.",
                "zh" to "有时，赢的唯一办法就是转身离开。",
                "ar" to "أحياناً السبيل الوحيد للفوز هو الانسحاب.",
                "hi" to "कभी-कभी जीतने का एकमात्र तरीक़ा है—चले जाना।",
                "th" to "บางครั้งทางเดียวที่จะชนะคือการเดินจากไป",
                "tr" to "bazen kazanmanın tek yolu çekip gitmektir.",
                "vi" to "đôi khi cách duy nhất để thắng là bỏ đi.",
                "in" to "kadang satu-satunya cara menang adalah pergi."
            )
        ),
        FsocQuote(
            "we are fsociety. we are finally free. we are finally awake.",
            mapOf(
                "ru" to "мы — fsociety. мы наконец свободны. мы наконец проснулись.",
                "de" to "wir sind fsociety. wir sind endlich frei. wir sind endlich wach.",
                "es" to "somos fsociety. al fin somos libres. al fin estamos despiertos.",
                "fr" to "nous sommes fsociety. nous sommes enfin libres. nous sommes enfin éveillés.",
                "it" to "siamo fsociety. siamo finalmente liberi. siamo finalmente svegli.",
                "pt" to "somos a fsociety. estamos finalmente livres. estamos finalmente acordados.",
                "ja" to "我々が fsociety だ。ついに自由になり、ついに目覚めた。",
                "ko" to "우리가 fsociety다. 우리는 마침내 자유다. 우리는 마침내 깨어났다.",
                "zh" to "我们就是 fsociety。我们终于自由，终于觉醒。",
                "ar" to "نحن fsociety. أخيراً صرنا أحراراً. أخيراً صحونا.",
                "hi" to "हम ही fsociety हैं। हम आख़िरकार आज़ाद हैं। हम आख़िरकार जाग चुके हैं।",
                "th" to "เราคือ fsociety เราเป็นอิสระแล้วในที่สุด เราตื่นแล้วในที่สุด",
                "tr" to "biz fsociety'yiz. sonunda özgürüz. sonunda uyandık.",
                "vi" to "chúng tôi là fsociety. cuối cùng chúng tôi đã tự do. cuối cùng chúng tôi đã thức tỉnh.",
                "in" to "kami adalah fsociety. kami akhirnya bebas. kami akhirnya bangun."
            )
        )
    )
    private var quoteHideRunnable: Runnable? = null

    // Active when the user picked the fsociety theme — flips a handful of
    // strings into Mr.Robot-flavored variants and surfaces the mask + the
    // terminal-style header/prompt. Default false so every other theme is
    // visually unchanged.
    private var fsocietyMode = false
    private var speedSheet: com.google.android.material.bottomsheet.BottomSheetDialog? = null
    private var detailsSheet: com.google.android.material.bottomsheet.BottomSheetDialog? = null
    private var twoColumns=false
    private var fittedViewport=""
    private var portraitLayout = emptyList<Pair<View, android.widget.LinearLayout.LayoutParams>>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        twoColumns=false
        fittedViewport=""
        portraitLayout = emptyList()
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val app = requireActivity().application as App
        val settings = app.loadSettings()

        // fsociety overlay: surface theme-specific decoration only when the
        // fsociety theme is active so other themes stay clean. The visibility
        // toggle here keeps the layout flow identical for every other theme.
        fsocietyMode = settings.themeMode == com.smarttools.netguard.model.ThemeMode.FSOCIETY
        binding.homeMap.setTerminalTypography(fsocietyMode)
        val maskVis = if (fsocietyMode) View.VISIBLE else View.GONE
        binding.tvFsocHeader.visibility = View.GONE
        binding.tvFsocPrompt.visibility = maskVis

        if (fsocietyMode) {
            binding.tvFsocPrompt.text = "root@fsociety:~$ [ OFF ]"
        }

        binding.serverCard.setOnClickListener {
            androidx.navigation.fragment.NavHostFragment.findNavController(this).navigate(R.id.nav_profiles)
        }
        binding.serverCard.setOnLongClickListener {
            val p=viewModel.connectionProfile.value.second
            if(p!=null)com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle(p.name).setMessage(p.displayProtocol).setPositiveButton(android.R.string.ok,null).show()
            true
        }
        val speedContent=binding.speedCard
        (speedContent.parent as ViewGroup).removeView(speedContent)
        speedContent.setContentPadding(dp(22),dp(24),dp(22),dp(24))
        speedContent.setCardBackgroundColor(themeColor(com.google.android.material.R.attr.colorSurface))
        for(tv in listOf(binding.tvSpeedStage,binding.tvSpeedResult,binding.tvSpeedError)) {
            tv.textSize=16f; tv.setPadding(0,dp(10),0,dp(10))
        }
        speedSheet=com.google.android.material.bottomsheet.BottomSheetDialog(requireContext()).apply {
            setContentView(androidx.core.widget.NestedScrollView(requireContext()).apply { addView(speedContent) })
            setOnShowListener { behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED }
            setOnDismissListener { viewModel.cancelSpeedTest() }
        }
        binding.homeMap.overview=true
        binding.homeMap.setVectorMap(R.drawable.world_map_clean)
        binding.homeMap.setLocations(GeoLookup.getUserLocation(),null)
        binding.btnDetails.setOnClickListener { showTrafficDetails() }
        binding.layoutStats.setOnClickListener { showTrafficDetails() }
        binding.root.addOnLayoutChangeListener { _,_,_,_,_,_,_,_,_ -> fitHome() }

        binding.btnConnect.setOnClickListener {
            val state = viewModel.connectionState.value
            if (state is ConnectionState.Disconnected || state is ConnectionState.Error) {
                val act = activity as? MainActivity ?: return@setOnClickListener
                // No server picked yet → run the auto-select probe instead of
                // failing with "No profile selected". autoSelectAndConnect
                // itself surfaces a toast if no servers are added at all.
                if (viewModel.selectedProfile.value == null) {
                    act.requestVpnPermissionAndAutoSelect()
                } else {
                    act.requestVpnPermissionAndConnect()
                }
            } else {
                viewModel.disconnect()
            }
        }

        binding.btnAutoSelect.setOnClickListener {
            viewModel.autoSelectAndConnect()
        }

        // Speed test setup
        if (settings.showSpeedTest) {
            binding.btnSpeedTest.setOnClickListener {
                binding.speedCard.visibility=View.VISIBLE
                speedSheet?.show()
                viewModel.runSpeedTest()
            }
        }
        binding.btnCancelSpeed.setOnClickListener { viewModel.cancelSpeedTest() }

        updateSessionStats()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.connectionState.collect { state ->
                        updateUI(state)
                        if (state is ConnectionState.Connected) {
                            updateTimer(state.startTimeMs)
                        } else {
                            cancelTimer()
                            binding.tvTimer.text = "00:00"
                        }
                        // Speed test visibility
                        if (settings.showSpeedTest) {
                            binding.layoutSpeedTest.visibility =
                                if (state is ConnectionState.Connected) View.VISIBLE else View.GONE
                            if (state !is ConnectionState.Connected) {
                                binding.tvSpeedResult.visibility = View.GONE
                            }
                        }
                        if (state is ConnectionState.Disconnected) {
                            updateSessionStats()
                        }
                    }
                }
                launch {
                    viewModel.trafficStats.collect { stats ->
                        renderTraffic(stats)
                        binding.tvDownTotal.text = TrafficFormatter.formatBytes(stats.rxBytes)
                        binding.tvUpTotal.text = TrafficFormatter.formatBytes(stats.txBytes)
                    }
                }
                launch {
                    var mapLookup: Job? = null
                    viewModel.connectionProfile.collect { (state, profile) ->
                        // Cancel without joining: a blocking lookup for the old
                        // server must never delay the new name/marker. Returning
                        // from withContext is cancellable, so it cannot repaint
                        // the map after a switch or after this view stops.
                        mapLookup?.cancel()
                        binding.tvProfileName.text = profile?.name ?: getString(R.string.no_profile_selected)
                        binding.tvProfileProtocol.text=profile?.displayProtocol ?: ""
                        binding.serverCard.contentDescription=listOfNotNull(profile?.name,profile?.displayProtocol,getString(R.string.home_choose_server)).joinToString(" · ")
                        if (!settings.showConnectionMap) return@collect

                        val connected = state is ConnectionState.Connected && profile != null
                        val namedLocation = if (connected) profile?.let { GeoLookup.fromProfileName(it.name) } else null
                        binding.homeMap.setServerLabel(if(connected)profile?.name else null)
                        binding.homeMap.setLocations(GeoLookup.getUserLocation(),namedLocation)
                        binding.homeMap.setConnected(connected)
                        if (!connected || profile == null) return@collect

                        mapLookup = launch {
                            val proxy = com.smarttools.netguard.core.CredentialManager.speedProxy(profile.protocol.usesRelay)
                            val serverLocation = withContext(Dispatchers.IO) {
                                val exit = proxy?.let { GeoLookup.fromTunnel(it) }
                                exit ?: namedLocation ?: if (profile.protocol.usesRelay) null else GeoLookup.fromIp(profile.address)
                            }
                            if (proxy != null && !com.smarttools.netguard.core.CredentialManager.isCurrent(proxy)) return@launch
                            binding.homeMap.setLocations(GeoLookup.getUserLocation(),serverLocation)
                            binding.homeMap.setConnected(true)
                            // Refresh only during an active connection. This job
                            // owns both locations; no independent callback can
                            // replace the active server with the saved selection.
                            val userLocation = withContext(Dispatchers.IO) { GeoLookup.fetchUserLocation() }
                            if (userLocation != null) {
                                binding.homeMap.setLocations(userLocation,serverLocation)
                            }
                        }
                    }
                }
                launch {
                    viewModel.autoSelecting.collect { selecting ->
                        binding.btnAutoSelect.isEnabled = !selecting
                        // Theme changes reapply text colors. Alpha preserves the
                        // busy state through Connected and avoids text/spinner overlap.
                        binding.btnAutoSelect.alpha = if (selecting) 0f else 1f
                        binding.progressAutoSelect.visibility = if (selecting) View.VISIBLE else View.GONE
                    }
                }
                launch {
                    viewModel.autoSelectMessage.collect { msg ->
                        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                        // Drop the replay cache — otherwise navigating
                        // away and back to Home replays the same toast.
                        viewModel.consumeAutoSelectMessage()
                    }
                }
                // Speed test collectors
                if (settings.showSpeedTest) {
                    launch {
                        viewModel.speedTesting.collect { testing ->
                            binding.btnSpeedTest.isEnabled = !testing
                            binding.btnSpeedTest.alpha = if (testing) 0f else 1f
                            binding.progressSpeedTest.visibility = if (testing) View.VISIBLE else View.GONE
                            binding.btnCancelSpeed.visibility = if (testing) View.VISIBLE else View.GONE
                            updateSpeedCard()
                        }
                    }
                    launch {
                        viewModel.speedStage.collect { stage ->
                            binding.tvSpeedStage.visibility = if (stage == null) View.GONE else View.VISIBLE
                            binding.tvSpeedStage.text = stage?.let { getString(when (it) {
                                SpeedTester.Stage.LATENCY -> R.string.speed_latency_stage
                                SpeedTester.Stage.DOWNLOAD -> R.string.speed_download_stage
                                SpeedTester.Stage.UPLOAD -> R.string.speed_upload_stage
                            }) }
                            updateSpeedCard()
                        }
                    }
                    launch {
                        viewModel.speedError.collect { error ->
                            binding.tvSpeedError.visibility = if (error == null) View.GONE else View.VISIBLE
                            binding.tvSpeedError.text = error?.let { getString(when (it) {
                                MainViewModel.SpeedError.NOT_READY -> R.string.speed_not_ready
                                MainViewModel.SpeedError.FAILED -> R.string.speed_failed
                                MainViewModel.SpeedError.TIMEOUT -> R.string.speed_timeout
                            }) }
                            updateSpeedCard()
                        }
                    }
                    launch {
                        viewModel.speedResult.collect { result ->
                            binding.tvSpeedResult.visibility = if (result == null) View.GONE else View.VISIBLE
                            if (result != null) {
                                fun rate(value: Double) = if (value < 0) getString(R.string.speed_unmeasured)
                                    else getString(R.string.speed_value, String.format("%.1f", value))
                                val latency = if (result.pingMs < 0) getString(R.string.speed_unmeasured)
                                    else getString(R.string.speed_latency_value, result.pingMs)
                                binding.tvSpeedResult.text = getString(R.string.speed_results,
                                    rate(result.downloadMbps), rate(result.uploadMbps), latency)
                            }
                            updateSpeedCard()
                        }
                    }
                }
            }
        }
    }

    private fun updateSpeedCard() {
        // This content lives in the result sheet, never in the fixed Home viewport.
        binding.speedCard.visibility=View.VISIBLE
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) {
            updateSessionStats()
        }
    }

    private fun updateSessionStats() {
        val app = requireActivity().application as App
        val statsRepo = app.statsRepository
        val mode = app.loadSettings().trafficStatsMode
        binding.historyCard.visibility = if (mode == TrafficStatsMode.HIDDEN) View.GONE else View.VISIBLE

        when (mode) {
            TrafficStatsMode.CHART -> {
                binding.trafficChart.visibility = if(isCompact())View.GONE else View.VISIBLE
                binding.layoutSessionStats.visibility = View.VISIBLE
                binding.trafficChart.setData(statsRepo.getDailyHistory(7))
            }
            TrafficStatsMode.SIMPLE -> {
                binding.trafficChart.visibility = View.GONE
                binding.layoutSessionStats.visibility = View.VISIBLE
            }
            TrafficStatsMode.HIDDEN -> {
                binding.trafficChart.visibility = View.GONE
                binding.layoutSessionStats.visibility = View.GONE
            }
        }

        if (mode != TrafficStatsMode.HIDDEN) {
            val stats = statsRepo.getStats()
            binding.tvStatsToday.text = TrafficFormatter.formatBytes(stats.todayRx + stats.todayTx)
            binding.tvStatsWeek.text = TrafficFormatter.formatBytes(stats.weekRx + stats.weekTx)
            binding.tvStatsTotal.text = TrafficFormatter.formatBytes(stats.totalRx + stats.totalTx)
        }
    }

    private fun updateUI(state: ConnectionState) {
        binding.tvStatus.setOnClickListener(null)
        binding.btnConnect.text=getString(if(state.isActive)R.string.disconnect else R.string.connect)
        val backdrop=requireActivity().findViewById<com.smarttools.netguard.widget.LiquidBackdrop>(R.id.liquid_backdrop)
        val shellState=when(state){is ConnectionState.Connected -> "ON";is ConnectionState.Connecting -> "WAIT";else -> "OFF"}
        binding.tvFsocPrompt.text=if(fsocietyMode) "root@fsociety:~$ [ $shellState ]" else "[ VPN / $shellState ]"
        when (state) {
            is ConnectionState.Disconnected -> {
                binding.tvStatus.text = getString(R.string.status_disconnected)
                binding.tvStatus.setTextColor(backdrop.foreground)
                binding.btnConnect.contentDescription = getString(R.string.connect)
                stopPulse()
            }
            is ConnectionState.Connecting -> {
                binding.tvStatus.text = getString(R.string.status_connecting)
                binding.tvStatus.setTextColor(backdrop.foreground)
                binding.btnConnect.contentDescription = getString(R.string.disconnect)
                stopPulse()
            }
            is ConnectionState.Connected -> {
                binding.tvStatus.text = getString(R.string.status_connected)
                binding.tvStatus.setTextColor(backdrop.foreground)
                binding.btnConnect.contentDescription = getString(R.string.disconnect)
                stopPulse()
            }
            is ConnectionState.Error -> {
                binding.tvStatus.text = getString(R.string.status_disconnected)
                binding.tvStatus.setOnClickListener { com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                    .setMessage(humanizeConnError(state.message)).setPositiveButton(android.R.string.ok,null).show() }
                binding.tvStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.status_error))
                binding.btnConnect.contentDescription = getString(R.string.connect)
                stopPulse()
            }
        }
        renderTraffic()
    }

    private fun renderTraffic(stats: com.smarttools.netguard.service.TunnelVpnService.TrafficSnapshot = viewModel.trafficStats.value) {
        val connected = viewModel.connectionState.value is ConnectionState.Connected
        val captionsHidden = (binding.layoutStats.getChildAt(0) as ViewGroup).getChildAt(0).visibility == View.GONE
        binding.tvDownSpeed.text = (if (captionsHidden) "↓" else "") + TrafficFormatter.formatSpeed(if (connected) stats.rxSpeed else 0)
        binding.tvUpSpeed.text = (if (captionsHidden) "↑" else "") + TrafficFormatter.formatSpeed(if (connected) stats.txSpeed else 0)
        binding.tvDownSpeed.contentDescription = getString(R.string.home_download) + " " + binding.tvDownSpeed.text
        binding.tvUpSpeed.contentDescription = getString(R.string.home_upload) + " " + binding.tvUpSpeed.text
    }

    private fun dp(n:Int)=(n*resources.displayMetrics.density).toInt()
    private fun isCompact()=binding.root.height>0 && binding.root.height/resources.displayMetrics.density<540 || resources.configuration.fontScale>1.25f
    private fun setStatusSize(minSp: Int, maxSp: Int) {
        // Reset wrap-content's measured font before changing the autosize range.
        // Otherwise expanding from the compact viewport keeps the small font.
        androidx.core.widget.TextViewCompat.setAutoSizeTextTypeWithDefaults(binding.tvStatus, android.widget.TextView.AUTO_SIZE_TEXT_TYPE_NONE)
        binding.tvStatus.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, maxSp.toFloat())
        // Measure the actual TextView: fallback fonts and locale-specific minimum
        // line metrics can be taller than Paint/StaticLayout's default metrics.
        binding.tvStatus.minimumHeight = 0
        binding.tvStatus.measure(
            View.MeasureSpec.makeMeasureSpec(binding.tvStatus.width.coerceAtLeast(1), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        binding.tvStatus.minimumHeight = binding.tvStatus.measuredHeight
        androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(binding.tvStatus,minSp,maxSp,1,android.util.TypedValue.COMPLEX_UNIT_SP)
    }
    private fun fitHome() {
        if(_binding==null)return
        val root=binding.root
        val d=resources.displayMetrics.density
        val height=root.height/d
        if(height<=0)return
        val viewport="${root.width}:${root.height}:${resources.configuration.fontScale}"
        if(viewport==fittedViewport)return
        fittedViewport=viewport
        val compact=isCompact()
        binding.tvFsocHeader.visibility=if(fsocietyMode && !compact && height>=580)View.VISIBLE else View.GONE
        binding.btnAutoSelect.setText(if(compact)R.string.home_auto_select_short else R.string.auto_select)
        binding.btnSpeedTest.setText(if(compact)R.string.home_speed_test_short else R.string.speed_test)
        binding.btnAutoSelect.contentDescription=getString(R.string.auto_select)
        binding.btnSpeedTest.contentDescription=getString(R.string.speed_test)
        val showOverview = !compact && height>=580 && (requireActivity().application as App).loadSettings().showConnectionMap
        binding.homeMap.visibility=if(showOverview)View.VISIBLE else View.GONE
        val horizontal=if(compact)16 else 24
        val sidePadding = dp(horizontal)
        val topPadding = dp(if(compact)8 else 20)
        val bottomPadding = dp(8)
        if(root.paddingLeft!=sidePadding || root.paddingRight!=sidePadding || root.paddingTop!=topPadding || root.paddingBottom!=bottomPadding) {
            root.setPadding(sidePadding,topPadding,sidePadding,bottomPadding)
        }
        val chart=if((requireActivity().application as App).loadSettings().trafficStatsMode==TrafficStatsMode.CHART&&!compact)View.VISIBLE else View.GONE
        if(binding.trafficChart.visibility!=chart)binding.trafficChart.visibility=chart
        if (portraitLayout.isEmpty()) {
            portraitLayout = (0 until root.childCount).map { index ->
                val child = root.getChildAt(index)
                child to android.widget.LinearLayout.LayoutParams(child.layoutParams as android.widget.LinearLayout.LayoutParams)
            }
        }
        val landscapeColumns = root.width/d>500 && height<400
        if (twoColumns && !landscapeColumns) {
            portraitLayout.forEach { (child, _) -> (child.parent as? ViewGroup)?.removeView(child) }
            root.removeAllViews()
            root.orientation = android.widget.LinearLayout.VERTICAL
            portraitLayout.forEach { (child, params) -> root.addView(child, android.widget.LinearLayout.LayoutParams(params)) }
            twoColumns = false
        }
        // A short landscape window uses two columns rather than scrolling or shrinking the whole UI.
        if(!twoColumns && landscapeColumns) {
            val items=listOf(binding.connectionCard,binding.serverCard,binding.btnConnect,binding.layoutActionRow,binding.layoutStats,binding.historyCard)
            items.forEach { (it.parent as ViewGroup).removeView(it) }
            root.removeAllViews();root.orientation=android.widget.LinearLayout.HORIZONTAL
            val left=android.widget.LinearLayout(requireContext()).apply {orientation=android.widget.LinearLayout.VERTICAL}
            val right=android.widget.LinearLayout(requireContext()).apply {orientation=android.widget.LinearLayout.VERTICAL;setPadding(dp(16),0,0,0)}
            root.addView(left,android.widget.LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.MATCH_PARENT,1f))
            root.addView(right,android.widget.LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.MATCH_PARENT,1f))
            for(v in items) {
                val parent=if(v===binding.layoutActionRow || v===binding.layoutStats || v===binding.historyCard)right else left
                parent.addView(v,android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            twoColumns=true
        }
        // Without a map, the status becomes a spacious hero instead of collapsing
        // all controls into a stack at the top. Compact/two-column layouts stay fixed.
        val hero = !showOverview && !compact && !twoColumns
        (binding.connectionCard.layoutParams as android.widget.LinearLayout.LayoutParams).let { p ->
            val targetHeight = if(hero)0 else ViewGroup.LayoutParams.WRAP_CONTENT
            val targetWeight = if(hero)1f else 0f
            if(p.height!=targetHeight || p.weight!=targetWeight) {
                p.height=targetHeight;p.weight=targetWeight;binding.connectionCard.layoutParams=p
            }
        }
        val statusContent=binding.connectionCard.getChildAt(0) as android.widget.LinearLayout
        statusContent.gravity=if(hero)android.view.Gravity.CENTER_VERTICAL else android.view.Gravity.TOP
        statusContent.layoutParams.let {p ->
            val target=if(hero)ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT
            if(p.height!=target){p.height=target;statusContent.layoutParams=p}
        }
        (binding.layoutStats.layoutParams as android.widget.LinearLayout.LayoutParams).let {p ->
            val margin=dp(if(hero)20 else 0)
            if(p.topMargin!=margin){p.topMargin=margin;binding.layoutStats.layoutParams=p}
        }
        val tight=height<410 || resources.configuration.fontScale>1.6f
        if(tight) {
            // Retain distinct Connected/Connecting/Error state even in the shortest viewport.
            binding.connectionCard.visibility=View.VISIBLE
            setStatusSize(12,18)
            binding.tvTimer.visibility=View.GONE
            binding.tvFsocPrompt.visibility=View.GONE
            (binding.tvTimer.parent as View).visibility=View.GONE
            binding.tvProfileProtocol.visibility=View.GONE
            (binding.serverCard.getChildAt(0) as View).setPadding(dp(10),dp(6),dp(10),dp(6))
            binding.btnConnect.minHeight=dp(48)
            (binding.btnConnect.layoutParams as android.widget.LinearLayout.LayoutParams).let { p ->
                if(p.topMargin!=dp(6)){p.topMargin=dp(6);binding.btnConnect.layoutParams=p}
            }
            binding.layoutStats.setPadding(dp(10),0,dp(10),0)
            binding.btnAutoSelect.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP,11f)
            binding.btnSpeedTest.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP,11f)
            binding.btnDetails.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP,11f)
            // At extreme font sizes, the two live rates are a single-line readout.
            for(i in 0 until binding.layoutStats.childCount) {
                val column=binding.layoutStats.getChildAt(i) as ViewGroup
                column.getChildAt(0).visibility=View.GONE
                val speed=column.getChildAt(1)
                (speed.layoutParams as android.widget.LinearLayout.LayoutParams).let {p ->
                    if(p.topMargin!=0){p.topMargin=0;speed.layoutParams=p}
                }
            }
        } else {
            binding.connectionCard.visibility=View.VISIBLE
            setStatusSize(20,34)
            (binding.serverCard.getChildAt(0) as View).setPadding(dp(14),dp(14),dp(14),dp(14))
            binding.btnConnect.minHeight=dp(56)
            (binding.btnConnect.layoutParams as android.widget.LinearLayout.LayoutParams).let { p ->
                if(p.topMargin!=dp(12)){p.topMargin=dp(12);binding.btnConnect.layoutParams=p}
            }
            binding.layoutStats.setPadding(dp(16),dp(14),dp(16),dp(14))
            binding.btnAutoSelect.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP,12f)
            binding.btnSpeedTest.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP,12f)
            binding.btnDetails.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP,14f)
            for(i in 0 until binding.layoutStats.childCount) {
                val column=binding.layoutStats.getChildAt(i) as ViewGroup
                column.getChildAt(0).visibility=View.VISIBLE
                val speed=column.getChildAt(1)
                (speed.layoutParams as android.widget.LinearLayout.LayoutParams).let { p ->
                    if(p.topMargin!=dp(6)){p.topMargin=dp(6);speed.layoutParams=p}
                }
            }
            binding.tvProfileProtocol.visibility=View.VISIBLE
            binding.tvTimer.visibility=View.VISIBLE
            (binding.tvTimer.parent as View).visibility=View.VISIBLE
            binding.tvFsocPrompt.visibility=if(fsocietyMode)View.VISIBLE else View.GONE
        }
        renderTraffic()
    }
    private fun showTrafficDetails() {
        val repo=(requireActivity().application as App).statsRepository
        val stats=repo.getStats()
        val column=android.widget.LinearLayout(requireContext()).apply {
            orientation=android.widget.LinearLayout.VERTICAL;setPadding(dp(24),dp(24),dp(24),dp(28))
        }
        fun line(text:String,size:Float=16f) { column.addView(android.widget.TextView(requireContext()).apply {
            this.text=text;textSize=size;setPadding(0,dp(8),0,dp(8))
            typeface=if(size>20)com.smarttools.netguard.widget.AppTypography.heading(context) else com.smarttools.netguard.widget.AppTypography.body(context)
        }) }
        line(getString(R.string.home_details),24f)
        line(getString(R.string.stats_today)+": "+TrafficFormatter.formatBytes(stats.todayRx+stats.todayTx))
        line(getString(R.string.stats_week)+": "+TrafficFormatter.formatBytes(stats.weekRx+stats.weekTx))
        line(getString(R.string.stats_total)+": "+TrafficFormatter.formatBytes(stats.totalRx+stats.totalTx))
        val traffic=viewModel.trafficStats.value
        line(getString(R.string.home_download)+": "+TrafficFormatter.formatBytes(traffic.rxBytes))
        line(getString(R.string.home_upload)+": "+TrafficFormatter.formatBytes(traffic.txBytes))
        column.addView(com.smarttools.netguard.widget.TrafficChartView(requireContext()).apply {
            layoutParams=android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(100));setData(repo.getDailyHistory(7))
        })
        detailsSheet?.dismiss()
        detailsSheet=com.google.android.material.bottomsheet.BottomSheetDialog(requireContext()).apply {
            setOnShowListener { behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED }
            setContentView(android.widget.ScrollView(requireContext()).apply {addView(column)});show()
        }
    }

    private fun themeColor(attr: Int): Int {
        val value = android.util.TypedValue()
        requireContext().theme.resolveAttribute(attr, value, true)
        return if (value.resourceId != 0) ContextCompat.getColor(requireContext(), value.resourceId) else value.data
    }

    /**
     * Turn a raw connection-error message into something an ordinary user
     * understands. Messages we already write in Russian (whitelist hint,
     * "Нет сети", ...) are passed through; English/technical ones (raw xray
     * / socket exceptions like "failed to connect", "Connection timed out")
     * map to a short friendly line.
     */
    private fun humanizeConnError(msg: String): String {
        val m = msg.lowercase()
        return when {
            m.contains("нет сети") -> getString(com.smarttools.netguard.R.string.connection_no_network)
            m.contains("whitelist") || m.contains("белый список") || m.contains("белых спис") ->
                getString(com.smarttools.netguard.R.string.connection_whitelist_help)
            m.contains("сервер в комнате") -> getString(com.smarttools.netguard.R.string.connection_room_not_ready)
            m.contains("permission") -> getString(com.smarttools.netguard.R.string.connection_permission_help)
            m.contains("profile not found") || m.contains("no selected") ->
                getString(com.smarttools.netguard.R.string.connection_select_help)
            m.contains("timed out") || m.contains("timeout") ->
                getString(com.smarttools.netguard.R.string.connection_timeout_help)
            m.contains("refused") || m.contains("reset") || m.contains("unreachable") ||
                m.contains("no route") || m.contains("connect") ->
                getString(com.smarttools.netguard.R.string.connection_network_help)
            m.contains("tun2socks") || m.contains("xray") ->
                getString(com.smarttools.netguard.R.string.connection_interrupted_help)
            else -> getString(com.smarttools.netguard.R.string.connection_generic_help)
        }
    }

    private fun startPulse() {
        if (pulseAnimator == null) {
            pulseAnimator = ObjectAnimator.ofFloat(binding.btnConnect, "alpha", 1f, 0.94f).apply {
                duration = 2000
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                interpolator = AccelerateDecelerateInterpolator()
                start()
            }
        }
    }

    private fun stopPulse() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        binding.btnConnect.alpha = 1f
    }

    private fun cancelTimer() {
        timerRunnable?.let { _binding?.tvTimer?.removeCallbacks(it) }
        timerRunnable = null
    }

    private fun updateTimer(startMs: Long) {
        cancelTimer()
        val elapsed = System.currentTimeMillis() - startMs
        binding.tvTimer.text = TrafficFormatter.formatDuration(elapsed)
        val runnable = Runnable {
            if (_binding != null && viewModel.connectionState.value is ConnectionState.Connected) {
                updateTimer(startMs)
            }
        }
        timerRunnable = runnable
        binding.tvTimer.postDelayed(runnable, 1000)
    }

    private fun setFsocPrompt(base: String) {
        fsocPromptBase = base
        // Render immediately so the line updates without waiting for the
        // next blink tick.
        renderFsocPrompt()
    }

    private fun renderFsocPrompt() {
        // Cursor is ALWAYS in the string (width never changes); only its
        // foreground color toggles between phosphor green and transparent.
        // Using a width-stable character + color span avoids the visible
        // jitter that arises when the centered LinearLayout re-measures the
        // text after each "█" ↔ " " swap.
        val tv = _binding?.tvFsocPrompt ?: return
        val full = "$fsocPromptBase█"
        val span = android.text.SpannableString(full)
        val cursorColor = if (fsocCursorOn)
            android.graphics.Color.parseColor("#00FF41")
        else
            android.graphics.Color.TRANSPARENT
        span.setSpan(
            android.text.style.ForegroundColorSpan(cursorColor),
            full.length - 1, full.length,
            android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        tv.text = span
    }

    private fun startCursorBlink() {
        stopCursorBlink()
        val tick = object : Runnable {
            override fun run() {
                if (_binding == null) return
                fsocCursorOn = !fsocCursorOn
                renderFsocPrompt()
                cursorHandler.postDelayed(this, 500L)
            }
        }
        cursorBlinkRunnable = tick
        cursorHandler.postDelayed(tick, 500L)
        // Paint initial state
        renderFsocPrompt()
    }

    private fun stopCursorBlink() {
        cursorBlinkRunnable?.let { cursorHandler.removeCallbacks(it) }
        cursorBlinkRunnable = null
    }

    private fun showFsocQuote(q: FsocQuote) {
        val tv = _binding?.tvFsocQuote ?: return
        // Pick the translation for the current app locale. Falls back to
        // an English-only line when the locale isn't in the map, so the
        // line never goes blank or shows "— " with nothing after the dash.
        // Android historically uses "iw"/"in" for he/id; getLanguage() may
        // return either form depending on API level, so we normalize.
        val rawLang = java.util.Locale.getDefault().language
        val lang = when (rawLang) {
            "iw" -> "he"
            "in" -> "in" // keep legacy code; map keyed on "in" for Indonesian
            "id" -> "in"
            else -> rawLang
        }
        val translation = q.tr[lang]
        tv.text = if (translation == null || lang == "en") {
            q.en
        } else {
            "${q.en}\n— $translation"
        }
        // Cancel any pending hide so taps in quick succession show the new
        // quote for a full window each time.
        quoteHideRunnable?.let { cursorHandler.removeCallbacks(it) }
        tv.animate().cancel()
        tv.alpha = 0f
        tv.visibility = View.VISIBLE
        tv.animate().alpha(1f).setDuration(220).start()
        val hide = Runnable {
            _binding?.tvFsocQuote?.animate()
                ?.alpha(0f)
                ?.setDuration(400)
                ?.withEndAction { _binding?.tvFsocQuote?.visibility = View.GONE }
                ?.start()
        }
        quoteHideRunnable = hide
        cursorHandler.postDelayed(hide, 5000L)
    }

    private fun installProfileNameEasterEgg() {
        // Enlarge the tap surface — the bare "Aeza-Multi-x6" text is a
        // small target. Setting clickable+a generous padding extends the
        // hit zone without disturbing the visual layout.
        binding.tvProfileName.isClickable = true
        val density = resources.displayMetrics.density
        binding.tvProfileName.setPadding((8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt())
        binding.tvProfileName.setOnClickListener {
            android.util.Log.d("fsoc-egg", "tap ${profileTapCount + 1}/5")
            // Cancel any pending reset; restart the window from now.
            profileTapResetRunnable?.let { cursorHandler.removeCallbacks(it) }
            profileTapCount += 1
            if (profileTapCount >= 5) {
                showFsocQuote(fsocQuotes.random())
                profileTapCount = 0
            } else {
                // No progress hint — easter eggs should be silent until they
                // trigger. The 3s reset window lets a curious user tap
                // several times in a row without timing out.
                val reset = Runnable { profileTapCount = 0 }
                profileTapResetRunnable = reset
                cursorHandler.postDelayed(reset, 3000L)
            }
        }
    }

    override fun onDestroyView() {
        viewModel.cancelSpeedTest()
        speedSheet?.setOnDismissListener(null);speedSheet?.dismiss();speedSheet=null
        detailsSheet?.dismiss();detailsSheet=null
        cancelTimer()
        stopPulse()
        stopCursorBlink()
        profileTapResetRunnable?.let { cursorHandler.removeCallbacks(it) }
        quoteHideRunnable?.let { cursorHandler.removeCallbacks(it) }
        portraitLayout = emptyList()
        _binding = null
        super.onDestroyView()
    }
}
