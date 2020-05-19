package net.perfectdreams.spicymorenitta.routes.user.dashboard

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.url
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.delay
import kotlinx.html.*
import kotlinx.html.dom.append
import kotlinx.html.dom.create
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.list
import kotlinx.serialization.json.JSON
import net.perfectdreams.loritta.datawrapper.Background
import net.perfectdreams.loritta.datawrapper.DailyShopResult
import net.perfectdreams.spicymorenitta.SpicyMorenitta
import net.perfectdreams.spicymorenitta.application.ApplicationCall
import net.perfectdreams.spicymorenitta.http
import net.perfectdreams.spicymorenitta.locale
import net.perfectdreams.spicymorenitta.routes.UpdateNavbarSizePostRender
import net.perfectdreams.spicymorenitta.utils.*
import org.w3c.dom.*
import utils.Audio
import kotlin.browser.document
import kotlin.browser.window
import kotlin.dom.clear
import kotlin.dom.hasClass
import kotlin.js.Date

class DailyShopDashboardRoute(val m: SpicyMorenitta) : UpdateNavbarSizePostRender("/user/@me/dashboard/daily-shop") {
    override val keepLoadingScreen: Boolean
        get() = true
    var generatedAt = -1L

    override fun onRender(call: ApplicationCall) {
        super.onRender(call)

        SpicyMorenitta.INSTANCE.launch {
            fixDummyNavbarHeight(call)
            /* m.fixLeftSidebarScroll {
                switchContent(call)
            } */

            m.launch {
                val timeUntilMidnight = getTimeUntilUTCMidnight()
                info("The page will be automatically updated @ $timeUntilMidnight")
                delay(timeUntilMidnight)
                m.showLoadingScreen()
                regen(true)
                m.hideLoadingScreen()
            }

            m.launch {
                while (true) {
                    val timeElement = document.select<HTMLDivElement?>("#when-will-be-the-next-update")
                    if (timeElement != null) {
                        val timeInSeconds = getTimeUntilUTCMidnight() / 1_000

                        val s = timeInSeconds % 60
                        val m = (timeInSeconds / 60) % 60
                        val h = (timeInSeconds / (60 * 60)) % 24

                        debug("time in seconds: $timeInSeconds")
                        debug("h: $h")
                        debug("m: $m")
                        debug("s: $s")

                        timeElement.clear()
                        timeElement.append {
                            span {
                                if (h != 0L) {
                                    +"${h + 1} Horas"
                                } else if (m != 0L) {
                                    +"$m Minutos"
                                } else if (s != 0L) {
                                    +"$s Segundos"
                                }
                            }
                        }
                    }

                    delay(1_000)
                }
            }

            regen(false)

            m.hideLoadingScreen()
        }
    }

    suspend fun regen(keepRechecking: Boolean) {
        // ===[ DAILY SHOP ]===
        val dailyJob = m.async {
            while (true) {
                val payload = http.get<String> {
                    url("${window.location.origin}/api/v1/economy/daily-shop")
                }

                val result = kotlinx.serialization.json.JSON.nonstrict.parse(DailyShopResult.serializer(), payload)

                if (keepRechecking && generatedAt == result.generatedAt) {
                    info("Waiting for 5_000ms until we recheck the shop again, looks like it wasn't fully updated yet...")
                    delay(5_000)
                    continue
                }

                info("Shop was successfully updated! generatedAt = ${result.generatedAt}")
                generatedAt = result.generatedAt

                return@async result
            }
            throw RuntimeException("Should never happen!")
        }

        // ===[ USER BACKGROUNDS ]===
        val userBackgroundsJob = m.async {
            debug("Retrieving profiles & background info...")
            val payload = http.get<String> {
                url("${window.location.origin}/api/v1/users/@me/profiles,backgrounds")
            }

            debug("Retrieved profiles & background info!")
            val result = kotlinx.serialization.json.JSON.nonstrict.parse(UserInfoResult.serializer(), payload)
            return@async result
        }

        // ===[ USER PROFILE IMAGE ]===
        val profileWrapperJob = m.async {
            val profileWrapper = Image()
            debug("Awaiting load...")
            profileWrapper.awaitLoad("${window.location.origin}/api/v1/users/@me/profile?t=${Date().getTime()}")
            debug("Load complete!")
            profileWrapper
        }

        debug("await #1")
        val dailyShop = dailyJob.await()
        debug("await #2")
        val userBackgrounds = userBackgroundsJob.await()
        debug("await #3")
        val profileWrapper = profileWrapperJob.await()
        debug("await #4")

        val fanArtArtistsJob = m.async {
            val allArtists = dailyShop.backgrounds.mapNotNull { it.createdBy }.flatten().distinct()

            if (allArtists.isEmpty())
                return@async listOf<FanArtArtist>()

            val payload = http.get<String> {
                url("${window.location.origin}/api/v1/loritta/fan-arts?query=all&filter=${allArtists.joinToString(",")}")
            }

            JSON.nonstrict.parse(FanArtArtist.serializer().list, payload)
        }

        val fanArtArtists = fanArtArtistsJob.await()

        debug("Everything is retrieved! Let's go!")

        generateShop(dailyShop, userBackgrounds, profileWrapper, fanArtArtists)
    }

    fun getTimeUntilUTCMidnight(): Long {
        val date = Date()
        date.asDynamic().setUTCHours(24, 0, 0, 0)
        val now = Date().getTime()
        val diff = date.getTime().toLong() - now.toLong()
        return diff
    }

    fun generateShop(dailyShop: DailyShopResult, userBackgrounds: UserInfoResult, profileWrapper: Image, fanArtArtists: List<FanArtArtist>) {
        info("Generating Shop...")
        val entriesDiv = document.select<HTMLDivElement>("#bundles-content")
        entriesDiv.clear()

        entriesDiv.append {
            div {
                id = "daily-shop"

                div {
                    style = "text-align: center;"

                    img(src = "https://loritta.website/assets/img/fanarts/Loritta_17_-_Allouette.png") {
                        style = "width: 400px;"
                    }

                    h1 {
                        + "Loja Diária"
                    }

                    p {
                        + "Bem-vind@ a loja diária de itens! O lugar para comprar itens para o seu \"+perfil\" da Loritta!"
                    }
                    p {
                        + "Todo o dia as 00:00 UTC (21:00 no horário do Brasil) a loja é atualizada com novos itens! Então volte todo o dia para verificar ^-^"
                    }
                }

                div {
                    generateAd("5964074013", "Loritta Daily Shop")
                }

                div(classes = "shop-reset-timer") {
                    div(classes = "horizontal-line") {}

                    i(classes = "fas fa-stopwatch stopwatch") {}

                    div(classes = "shop-timer") {
                        div(classes = "shop-timer-date") {
                            id = "when-will-be-the-next-update"
                        }
                        div(classes = "shop-timer-subtitle") {
                            +"até a loja atualizar"
                        }
                    }
                }

                div(classes = "loritta-items-wrapper") {
                    for (background in dailyShop.backgrounds.sortedByDescending { it.rarity.getBackgroundPrice() }) {
                        val bought = background.internalName in userBackgrounds.backgrounds.map { it.internalName }

                        div(classes = "shop-item-entry rarity-${background.rarity.name.toLowerCase()}") {
                            div {
                                style = "position: relative;"

                                div {
                                    style = "overflow: hidden; line-height: 0;"

                                    canvas("canvas-background-preview") {
                                        id = "canvas-preview-${background.internalName}"
                                        width = "800"
                                        height = "600"
                                        style = "width: 400px; height: auto;"
                                    }
                                }

                                div(classes = "item-entry-information rarity-${background.rarity.name.toLowerCase()}") {
                                    div(classes = "item-entry-title rarity-${background.rarity.name.toLowerCase()}") {
                                        +(locale["backgrounds.${background.internalName}.title"])
                                    }
                                    div(classes = "item-entry-type") {
                                        +"Background"
                                    }
                                }

                                if (background.tag != null) {
                                    div(classes = "item-new-tag") {
                                        +locale[background.tag!!]
                                    }
                                }
                            }

                            div(classes = "item-user-information") {
                                if (bought) {
                                    i(classes = "fas fa-check") {
                                        style = "color: #80ff00;"
                                    }
                                    +" Comprado!"
                                } else {
                                    +"${background.rarity.getBackgroundPrice().toString()} Sonhos"
                                }
                            }
                        }
                    }
                }
            }
        }

        for (background in dailyShop.backgrounds) {
            val bought = background.internalName in userBackgrounds.backgrounds.map { it.internalName }
            val canvasPreview = document.select<HTMLCanvasElement>("#canvas-preview-${background.internalName}")

            m.launch {
                val (image) = LockerUtils.prepareBackgroundCanvasPreview(m, background, canvasPreview)

                canvasPreview.parentElement!!.parentElement!!.onClick {
                    openBackgroundInformation(userBackgrounds, background, bought, StaticBackgroundImage(image), profileWrapper, fanArtArtists)
                }
            }
        }
    }

    @Serializable
    class UserInfoResult(
            val profile: Profile,
            var backgrounds: MutableList<Background>
    )

    @Serializable
    class Profile(
            val money: Long
    )

    fun openBackgroundInformation(result: UserInfoResult, background: Background, alreadyBought: Boolean, backgroundImg: BackgroundImage, profileWrapper: Image, fanArtArtists: List<FanArtArtist>) {
        val modal = TingleModal(
                TingleOptions(
                        footer = true,
                        cssClass = arrayOf("tingle-modal--overflow")
                )
        )

        modal.setContent(
                document.create.div(classes = "item-shop-preview") {
                    div {
                        style = "flex-grow: 1;"
                        h1 {
                            style = "word-break: break-word; text-align: center;"

                            +(locale["backgrounds.${background.internalName}.title"])
                        }
                        div {
                            style = "margin-bottom: 10px;"
                            +(locale["backgrounds.${background.internalName}.description"])

                            if (background.createdBy != null) {
                                val artists = fanArtArtists.filter { it.id in background.createdBy!! }
                                if (artists.isNotEmpty()) {
                                    artists.forEach {
                                        div {
                                            val name = (it.info.override?.name ?: it.user?.name ?: it.info.name ?: it.id)

                                            +"Criado por "
                                            a(href = "/fanarts/${it.id}") {
                                                +name
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        if (background.set != null) {
                            div {
                                i {
                                    + "Parte do conjunto "

                                    b {
                                        +(locale["sets.${background.set}"])
                                    }
                                }
                            }
                        }
                    }

                    div(classes = "canvas-preview-wrapper") {
                        canvas("canvas-preview-only-bg") {
                            style = """width: 400px;"""
                            width = "800"
                            height = "600"
                        }

                        canvas("canvas-preview") {
                            style = """width: 400px;"""
                            width = "800"
                            height = "600"
                        }
                    }
                }
        )

        val cash = Audio("${loriUrl}assets/snd/css1_cash.wav")
        if (!alreadyBought) {
            val canBuy = result.profile.money >= background.rarity.getBackgroundPrice()
            val classes = if (canBuy) "button-discord-info" else "button-discord-disabled"
            modal.addFooterBtn("<i class=\"fas fa-gift\"></i> Comprar", "buy-button-modal button-discord $classes pure-button button-discord-modal") {
                if (canBuy) {
                    m.launch {
                        m.showLoadingScreen()
                        val response = http.post<io.ktor.client.statement.HttpResponse>("${loriUrl}api/v1/economy/daily-shop/buy/background/${background.internalName}") {
                            body = "{}"
                        }

                        if (response.status != HttpStatusCode.OK) {

                        }

                        visibleModal.select<HTMLElement>(".buy-button-modal")
                                .remove()

                        m.launch {
                            regen(false)
                            m.hideLoadingScreen()
                            cash.play()
                        }
                    }
                }
            }
        }

        modal.addFooterBtn("<i class=\"fas fa-times\"></i> Fechar", "button-discord pure-button button-discord-modal button-discord-modal-secondary-action") {
            modal.close()
        }

        modal.open()

        val openModal = visibleModal

        val canvasCheckout = visibleModal.select<HTMLCanvasElement>(".canvas-preview")
        val canvasCheckoutOnlyBg = visibleModal.select<HTMLCanvasElement>(".canvas-preview-only-bg")

        val canvasPreviewContext = (canvasCheckout.getContext("2d")!! as CanvasRenderingContext2D)
        val canvasPreviewOnlyBgContext = (canvasCheckoutOnlyBg.getContext("2d")!! as CanvasRenderingContext2D)
        if (backgroundImg is StaticBackgroundImage) {
            canvasPreviewContext
                    .drawImage(
                            backgroundImg.image,
                            (background.crop?.offsetX ?: 0).toDouble(),
                            (background.crop?.offsetY ?: 0).toDouble(),
                            (background.crop?.width ?: backgroundImg.image.width).toDouble(),
                            (background.crop?.height ?: backgroundImg.image.height).toDouble(),
                            0.0,
                            0.0,
                            800.0,
                            600.0
                    )
            canvasPreviewOnlyBgContext
                    .drawImage(
                            backgroundImg.image,
                            (background.crop?.offsetX ?: 0).toDouble(),
                            (background.crop?.offsetY ?: 0).toDouble(),
                            (background.crop?.width ?: backgroundImg.image.width).toDouble(),
                            (background.crop?.height ?: backgroundImg.image.height).toDouble(),
                            0.0,
                            0.0,
                            800.0,
                            600.0
                    )

            canvasPreviewContext.drawImage(profileWrapper, 0.0, 0.0)
        } else if (backgroundImg is AnimatedBackgroundImage) {
            // keep updating canvas
            m.launch {
                while (true) {
                    for (frame in backgroundImg.images) {
                        if (!openModal.hasClass("tingle-modal--visible"))
                            return@launch // modal foi fechado

                        canvasPreviewContext.clearRect(0.0, 0.0, 800.0, 600.0)
                        canvasPreviewContext
                                .drawImage(
                                        frame,
                                        0.0,
                                        0.0,
                                        800.0,
                                        600.0
                                )
                        canvasPreviewContext.drawImage(profileWrapper, 0.0, 0.0)
                        canvasPreviewOnlyBgContext.clearRect(0.0, 0.0, 800.0, 600.0)
                        canvasPreviewOnlyBgContext
                                .drawImage(
                                        frame,
                                        0.0,
                                        0.0,
                                        800.0,
                                        600.0
                                )

                        delay(backgroundImg.speed)
                    }
                }
            }
        }
    }

    @Serializable
    data class AnimatedBackground(
            val speed: Long,
            val frames: Array<String>
    )

    open class BackgroundImage()

    class StaticBackgroundImage(val image: Image) : BackgroundImage()

    class AnimatedBackgroundImage(val speed: Long, val images: List<Image>) : BackgroundImage()
}