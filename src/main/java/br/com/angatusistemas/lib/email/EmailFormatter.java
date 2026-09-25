package br.com.angatusistemas.lib.email;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Classe utilitária para formatação e validação de endereços de e-mail.
 *
 * <p><strong>Propósito:</strong> validar o formato de um endereço (RFC 5321/5322, com as
 * adaptações práticas descritas abaixo), normalizar, extrair domínio e parte local, mascarar
 * para exibição, montar {@code "Nome <e-mail>"} e <strong>recusar e-mails
 * temporários/descartáveis</strong>.</p>
 *
 * <p><strong>Quando usar:</strong> no cadastro e na troca de e-mail de usuários reais
 * ({@link #isValidNormal(String)}) e para mensagens de erro prontas para exibir
 * ({@link #getValidationErrorMessage(String)}).</p>
 *
 * <p><strong>O que é validado:</strong></p>
 * <ul>
 * <li>Espaços nas pontas são ignorados em toda a classe: {@code "x@mailinator.com "} é o mesmo
 * endereço que {@code "x@mailinator.com"}. Antes, o espaço final ia parar no domínio extraído
 * e fazia o domínio descartável passar direto pela checagem.</li>
 * <li>Até 254 caracteres no total (RFC 5321), até 64 na parte local e rótulos de domínio de até
 * 63. O tamanho é conferido antes de qualquer outra análise, e a análise não usa expressão
 * regular: um texto de 4 KB derrubava a validação antiga com {@code StackOverflowError}.</li>
 * <li>Parte local: letras e dígitos ASCII e os símbolos {@code ! # $ % & ' * + - / = ? ^ _ ` { | } ~}
 * da RFC 5322, em blocos separados por um único ponto. Aceita {@code o'brien@...}, como o
 * {@code <input type="email">} do navegador aceita — o servidor não deve recusar o que a tela
 * deixou passar. {@link #isValidStrict(String)} usa um conjunto conservador (letras, dígitos e
 * {@code + - _}).</li>
 * <li>Domínio: dois ou mais rótulos de letras, dígitos e hífen (sem hífen nas pontas) e domínio
 * de topo com duas letras ou mais, ou IDN em punycode ({@code xn--...}).</li>
 * </ul>
 *
 * <p><strong>Descartáveis:</strong> a lista de provedores conhecidos (permitidos) é consultada
 * primeiro e sempre vence. Depois, o domínio <em>e cada domínio pai</em> são procurados na lista
 * de descartáveis: {@code x@qualquer.mailinator.com} é recusado como {@code x@mailinator.com}. A
 * lista de descartáveis pode ser alterada com a aplicação no ar
 * ({@link #addDisposableDomain(String)}, {@link #removeDisposableDomain(String)}), com segurança
 * entre threads.</p>
 *
 * <p><strong>Exemplo:</strong></p>
 * <pre>
 * EmailFormatter.isValidNormal("usuario@gmail.com");        // true
 * EmailFormatter.isValidNormal("teste@mailinator.com");     // false (descartável)
 * EmailFormatter.format("Usuário", "usuario@exemplo.com");  // "Usuário &lt;usuario@exemplo.com&gt;"
 * EmailFormatter.getDomain("usuario@exemplo.com");          // "exemplo.com"
 * </pre>
 *
 * <p><strong>Limitações:</strong> valida o formato, não a existência — só um envio confirma que a
 * caixa existe. Parte local entre aspas, IP literal ({@code [1.2.3.4]}) e caracteres fora do ASCII
 * (SMTPUTF8) são recusados de propósito. A lista de descartáveis é estática e incompleta: serviços
 * novos surgem o tempo todo.</p>
 *
 * @author Angatu Sistemas
 * @see <a href="https://tools.ietf.org/html/rfc5322">RFC 5322</a>
 * @see <a href="https://tools.ietf.org/html/rfc5321">RFC 5321</a>
 */
public final class EmailFormatter {

	/**
	 * Tamanho máximo do endereço: o caminho SMTP tem 256 octetos, menos os sinais {@code <} e
	 * {@code >} (RFC 5321, 4.5.3.1.3). Conferido antes de qualquer outra análise.
	 */
	private static final int MAX_ADDRESS_LENGTH = 254;
	/** Tamanho máximo da parte local (RFC 5321, 4.5.3.1.1). */
	private static final int MAX_LOCAL_PART_LENGTH = 64;
	/**
	 * Tamanho máximo de um domínio (RFC 1035). Também limita o custo da busca por domínios pai em
	 * {@link #isDisposableDomain(String)}, que recebe texto de fora.
	 */
	private static final int MAX_DOMAIN_LENGTH = 253;
	/** Tamanho máximo de um rótulo de domínio (RFC 1035). */
	private static final int MAX_LABEL_LENGTH = 63;
	/** Símbolos "atext" da RFC 5322 aceitos na parte local, além de letras e dígitos ASCII. */
	private static final String LOCAL_PART_SYMBOLS = "!#$%&'*+-/=?^_`{|}~";
	/** Subconjunto conservador de símbolos usado por {@link #isValidStrict(String)}. */
	private static final String STRICT_LOCAL_PART_SYMBOLS = "+-_";
	/** Caracteres que obrigam o nome de exibição a sair entre aspas ("specials" da RFC 5322). */
	private static final String DISPLAY_NAME_SPECIALS = "()<>[]:;@\\,.\"";

	/**
	 * Domínios de e-mail temporário/descartável (bloqueados, com os subdomínios).
	 *
	 * <p>Conjunto concorrente porque {@link #addDisposableDomain(String)} e
	 * {@link #removeDisposableDomain(String)} o alteram com a aplicação no ar enquanto requisições
	 * o leem: um {@code HashSet} alterado durante uma leitura pode responder errado ou corromper a
	 * própria estrutura interna.</p>
	 *
	 * <p>Provedores e organizações reais que estavam aqui por engano saíram (Microsoft, Micro Focus,
	 * mail.com, Mail.ru, mail.de, mail.ee, Hushmail, Lycos, Mailfence, GMX, domínios da FastMail,
	 * MikroTik, Mailinblack, entre outros): recusavam clientes de verdade. As entradas repetidas
	 * também saíram.</p>
	 */
	private static final Set<String> DISPOSABLE_DOMAINS = concurrentSetOf(
			"tempmail.com", "10minutemail.com", "guerrillamail.com", "mailinator.com", "yopmail.com",
			"throwaway.email", "sharklasers.com", "guerrillamail.net", "guerrillamail.org", "guerrillamail.biz",
			"mailmetrash.com", "trashmail.com", "temp-mail.org", "tempmail.net", "tempinbox.com", "fakeinbox.com",
			"getnada.com", "mailnator.com", "dispostable.com", "spambox.us", "mintemail.com", "mytrashmail.com",
			"trash2009.com", "trashdevil.com", "trashymail.com", "tyldd.com", "uggsrock.com", "wegwerfmail.de",
			"wegwerfmail.net", "wegwerfmail.org", "wh4f.org", "whyspam.me", "willselfdestruct.com", "winemaven.info",
			"wronghead.com", "wuzup.net", "xagloo.com", "xemaps.com", "xents.com", "xmaily.com", "xoxy.net", "yep.it",
			"yogamaven.com", "yopmail.fr", "yopmail.net", "ypmail.webarnak.fr.eu.org", "maildrop.cc", "spam4.me",
			"spamspot.com", "tempemail.net", "temp-mail.net", "tempinbox.co", "tempomail.fr", "temporarily.de",
			"temporario.email", "temporary-mail.net", "temporaryemail.net", "temporayemail.net", "temporry.com",
			"temporry.net", "temporry.org", "temporryemail.com", "temporryemail.net", "temporryemail.org",
			"temporrymail.com", "temporrymail.net", "temporrymail.org", "temporrymail.co", "10minut.xyz",
			"10minute.com", "10minutemail.co.za", "10minutemail.net", "10minutemail.org", "10minutemail.us",
			"10minutemail.xyz", "10minutemailz.com", "10minutesmail.com", "10minutesmail.net", "10minutesmail.org",
			"10minutesmail.us", "10minutesmail.xyz", "1secmail.com", "1secmail.net", "1secmail.org",
			"20minutemail.com", "20minutemail.it", "20minutemail.net", "2prong.com", "30minutemail.com",
			"30minutemail.org", "33mail.com", "3d-painting.com", "3mail.fi", "4warding.com", "4warding.net",
			"4warding.org", "5mail.xyz", "60minutemail.com", "6mail.xyz", "7mail.xyz", "8mail.xyz", "9mail.xyz",
			"abyssmail.com", "acmemail.net", "aeneasmail.com", "airmailhub.com", "altmails.com", "amail.com",
			"amail4.me", "amail.club", "amail.to", "amail.xyz", "anonymbox.com", "antichef.com", "antichef.net",
			"antireg.com", "antireg.ru", "antispam.de", "antispam24.de", "antispammail.de", "armyspy.com",
			"artman-mail.com", "artman-mail.de", "artman-mail.net", "artman-mail.org", "averdov.com", "averdov.net",
			"averdov.org", "averdov.su", "averdov.xyz", "azazazatashkent.tk", "baxomale.ht.cx", "beefmilk.com",
			"bigstring.com", "binkmail.com", "bizmail.net", "bobmail.info", "bobmurch.com", "bofthew.com",
			"bongobongo.ga", "bongobongo.gq", "bongobongo.ml", "bongobongo.tk", "brefmail.com", "brennendesreich.de",
			"broadbandninja.com", "bsnow.net", "buon.club", "burnthespam.info", "burstmail.info", "busiwebs.com",
			"buygoldmail.com", "c2.hu", "c2.li", "c2.lv", "c2.si", "c2.vc", "cachedot.net", "card.zp.ua",
			"casualdx.com", "cbair.com", "cechire.com", "cek.pm", "cellurl.com", "centermail.com", "centermail.net",
			"chammy.info", "cheatmail.de", "chogmail.com", "choicemail1.com", "clixser.com", "cmail.club", "cmail.com",
			"cmail.net", "cmail.org", "coldemail.info", "cool.fr.nf", "courriel.fr.nf", "courrieltemporaire.com",
			"crapmail.org", "curryworld.de", "cust.in", "d3p.dk", "dacoolest.com", "dandikmail.com", "dayrep.com",
			"dcemail.com", "deadaddress.com", "deadspam.com", "deagot.com", "dealja.com", "despam.it", "despammed.com",
			"devnullmail.com", "dfgh.net", "digitalsanctuary.com", "dingbone.com", "discard.email", "discardmail.com",
			"discardmail.de", "disposableaddress.com", "disposableemail.com", "disposableemail.org",
			"disposableinbox.com", "dispose.it", "disposeamail.com", "disposemail.com", "divermail.com",
			"dm.w3sites.net", "dodgeit.com", "dodgit.com", "dodgit.org", "doiea.com", "dolphinnet.net", "donebyng.com",
			"dotman.de", "dotmsg.com", "drdrb.com", "drdrb.net", "drdrb.org", "drdrb.ru", "drdrb.su", "drdrb.xyz",
			"drdrba.com", "drdrba.net", "drdrba.org", "drdrba.ru", "drdrba.su", "drdrba.xyz", "drdrbb.com",
			"drdrbb.net", "drdrbb.org", "drdrbb.ru", "drdrbb.su", "drdrbb.xyz", "drdrbc.com", "drdrbc.net",
			"drdrbc.org", "drdrbc.ru", "drdrbc.su", "drdrbc.xyz", "drdrbd.com", "drdrbd.net", "drdrbd.org",
			"drdrbd.ru", "drdrbd.su", "drdrbd.xyz", "drdrbe.com", "drdrbe.net", "drdrbe.org", "drdrbe.ru", "drdrbe.su",
			"drdrbe.xyz", "dropmail.me", "dt.com", "duam.net", "dudmail.com", "dump-email.info", "dumpandjunk.com",
			"dumpmail.de", "dumpyemail.com", "dwjworld.com", "e-mail.com", "e-mail.org", "e4ward.com",
			"easytrashmail.com", "einrot.com", "einrot.de", "eintagsmail.de", "email60.com", "emailacc.com",
			"emailage.ga", "emailage.gq", "emailage.ml", "emailage.tk", "emaildienst.de", "emailfake.com",
			"emailforyou.net", "emailgo.de", "emailias.com", "emailigo.com", "emailinfive.com", "emailisvalid.com",
			"emaillime.com", "emailmiser.com", "emailproxsy.com", "emailresort.com", "emails.ga", "emails.gq",
			"emails.ml", "emails.tk", "emailsense.com", "emailspam.cf", "emailspam.ga", "emailspam.gq", "emailspam.ml",
			"emailspam.tk", "emailsubject.com", "emailtemporario.com", "emailtemporario.net", "emailtemporario.org",
			"emailtemporario.xyz", "emailtemporario.com.br", "emailtemporario.net.br", "emailtemporario.org.br",
			"emailtemporario.xyz.br", "emailtmp.com", "emailto.org", "emailwarden.com", "emailx.at", "emailx.net",
			"emailx.org", "emailx.xyz", "emailxfer.com", "emailz.ga", "emailz.gq", "emailz.ml", "emailz.tk",
			"eml.pp.ua", "emlhub.com", "emlpro.com", "emltmp.com", "empireanime.ga", "empireanime.gq",
			"empireanime.ml", "empireanime.tk", "emz.net", "enterto.com", "ephemail.net", "ero-tube.org",
			"etranquil.com", "etranquil.net", "etranquil.org", "evopo.com", "explodemail.com", "express.net.ua",
			"eyepaste.com", "f4k.es", "f5.si", "facebook-email.ga", "facebook-email.gq", "facebook-email.ml",
			"facebook-email.tk", "facebookmail.ga", "facebookmail.gq", "facebookmail.ml", "facebookmail.tk",
			"facebookmailer.ga", "facebookmailer.gq", "facebookmailer.ml", "facebookmailer.tk", "fake-email.pp.ua",
			"fake-mail.cf", "fake-mail.ga", "fake-mail.gq", "fake-mail.ml", "fake-mail.tk", "fakebox.ga", "fakebox.gq",
			"fakebox.ml", "fakebox.tk", "fakeemail.de", "fakeinbox.cf", "fakeinbox.ga", "fakeinbox.gq", "fakeinbox.ml",
			"fakeinbox.tk", "fakemail.fr", "fakemail.net", "fakemail.org", "fakemail.xyz", "fakemailgenerator.com",
			"fakemailz.com", "fammix.com", "fansworldwide.de", "fantasymail.de", "fastacura.com", "fastchevy.com",
			"fastchrysler.com", "fastcruz.com", "fastdodge.com", "fastholden.com", "fasthonda.com", "fasthummer.com",
			"fastinfiniti.com", "fastjaguar.com", "fastjeep.com", "fastkia.com", "fastlamborghini.com",
			"fastlandrover.com", "fastlexus.com", "fastmazda.com", "fastmitsubishi.com", "fastnissan.com",
			"fastpontiac.com", "fastporsche.com", "fastsaab.com", "fastsaturn.com", "fastscion.com", "fastsubaru.com",
			"fastsuzuki.com", "fasttoyota.com", "fastvolkswagen.com", "fastvolvo.com", "fauxmail.com", "femail.ga",
			"femail.gq", "femail.ml", "femail.tk", "ficken.de", "figshot.com", "fiifke.com", "filzmail.com",
			"fivemail.de", "fixmail.tk", "fizmail.com", "flashbox.5v.pl", "fleckens.hu", "fliegend.com", "flurred.com",
			"fly-ts.de", "flyspam.com", "foobar.com", "forgetmail.com", "fr33mail.info", "frapmail.com",
			"free-email.ga", "free-email.gq", "free-email.ml", "free-email.tk", "freecoolemail.com",
			"freefattymovies.com", "freemail.bo.pl", "freemail.c3.cx", "freemail.ms", "freemail.xxx", "freemails.ga",
			"freemails.gq", "freemails.ml", "freemails.tk", "freemeil.ga", "freemeil.gq", "freemeil.ml", "freemeil.tk",
			"freerubik.ru", "freeschoolgirls.net", "freesmail.net", "freeweb.email", "freeweb.org", "freeyellow.com",
			"friendlymail.net", "front14.org", "ftp.sh", "fullmail.com", "funkymail.de", "fux0ringduh.com", "fw.mn",
			"garbagemail.org", "gardenscape.ca", "garliclife.com", "gatamail.com", "gaumesnil.com",
			"gehensiemirnichtaufdensack.de", "gelitik.in", "get1mail.com", "get2mail.fr", "getairmail.com",
			"getcloudmail.com", "getmails.eu", "getonemail.com", "getonemail.net", "getsimpleemail.com", "gett.ee",
			"ghosttexter.de", "giantmail.de", "ginzi.be", "ginzi.co.uk", "ginzi.es", "ginzi.eu", "ginzi.fr",
			"ginzi.it", "ginzi.net", "ginzi.org", "ginzi.xyz", "girlmail.ws", "girlsindetention.com", "gishpuppy.com",
			"givehimthefinger.info", "givememail.club", "goat.si", "google-mail.ga", "google-mail.gq",
			"google-mail.ml", "google-mail.tk", "googlemail.ga", "googlemail.gq", "googlemail.ml", "googlemail.tk",
			"googlegroups.ga", "googlegroups.gq", "googlegroups.ml", "googlegroups.tk", "gorillaswithdirtyarmpits.com",
			"gotmail.com", "gotmail.net", "gotmail.org", "gowikitest.com", "grafischeweb.de", "grandmamail.com",
			"grandmasmail.com", "great-host.in", "greensloth.com", "grr.la", "gs-arc.org", "gsredcross.org",
			"gsrv.co.uk", "guerillamail.biz", "guerillamail.com", "guerillamail.net", "guerillamail.org",
			"guerrillamailblock.com", "gustr.com", "h.mintemail.com", "h8s.org", "h9s.org", "hablas.com",
			"haltospam.com", "harakirimail.com", "hartbot.de", "hat-geld.de", "hatespam.org", "hawrong.com",
			"haydoo.com", "hazelnutbread.com", "hecat.es", "hellodream.mobi", "hellokitty.com", "helmsen.net",
			"herp.in", "hidemail.de", "hidzz.com", "hmamail.com", "hochsitzungen.de", "hoer.pw", "holl.ga", "holl.gq",
			"holl.ml", "holl.tk", "hopemail.biz", "hotpop.com", "hulapla.de", "humn.ws.gd", "i2pmail.org",
			"i6.cloudns.cc", "i6.cloudns.cf", "i6.cloudns.ga", "i6.cloudns.gq", "i6.cloudns.ml", "i6.cloudns.tk",
			"i6.cloudns.xyz", "i6.xyz", "iaoss.com", "icantbelieveineedtoexplainthis.com", "icemail.com", "ichigo.me",
			"ieatspam.eu", "ieatspam.info", "ieh-mail.de", "ihateyoualot.info", "ihatespam.com", "ihatespam.info",
			"ihatespam.net", "ihatespam.org", "ihatespam.xyz", "ihatespamming.com", "ihatespamming.net",
			"ihatespamming.org", "ihatespamming.xyz", "iheartspam.com", "iheartspam.net", "iheartspam.org",
			"iheartspam.xyz", "iheartspamming.com", "iheartspamming.net", "iheartspamming.org", "iheartspamming.xyz",
			"iheartspammy.com", "iheartspammy.net", "iheartspammy.org", "iheartspammy.xyz", "ikbenspamvrij.nl",
			"ilovespam.com", "ilovespam.net", "ilovespam.org", "ilovespam.xyz", "ilovespamming.com",
			"ilovespamming.net", "ilovespamming.org", "ilovespamming.xyz", "imails.info", "imgof.com", "imgv.de",
			"immo-gerance.info", "imstations.com", "inbax.tk", "inbox.si", "inboxalias.com", "inboxbear.com",
			"inboxclean.com", "inboxclean.org", "inboxdesign.com", "inboxed.pw", "inboxkitten.com", "inboxmail.eu",
			"inboxme.eu", "inboxproxy.com", "inboxstore.me", "incognitomail.com", "incognitomail.net",
			"incognitomail.org", "incognitomail.xyz", "incognitomail.com.br", "incognitomail.net.br",
			"incognitomail.org.br", "incognitomail.xyz.br", "ineec.net", "inerted.com", "infocom.zp.ua", "inggo.org",
			"insanum.xyz", "insorg-mail.info", "instaddr.com", "instantemailaddress.com", "instantmail.fr",
			"instantmailaddress.com", "ipoo.org", "irish2k.com", "iwi.net", "jajxz.com", "jdmadventures.com",
			"jellyfishpink.net", "jetable.com", "jetable.fr.nf", "jetable.net", "jetable.org", "jetable.pp.ua",
			"jetableemail.com", "jetablemail.com", "jmail.ovh", "jmail.ro", "jmailr.com", "jmailz.com", "job.cf",
			"job.ga", "job.gq", "job.ml", "job.tk", "junk1.com", "junkmail.com", "junkmail.ga", "junkmail.gq",
			"junkmail.ml", "junkmail.tk", "junkmailgenerator.com", "junkme.info", "junkpile.net", "junkstuff.com",
			"juyouxi.com", "jwork.ru", "k2-herberg.de", "k2-herberg.info", "k2-herberg.net", "k2-herberg.org",
			"k2-herberg.xyz", "k2-herberg.com.br", "k2-herberg.net.br", "k2-herberg.org.br", "k2-herberg.xyz.br",
			"k2-herberg.info.br", "k2-herberg.ru", "k2-herberg.su", "k2-herberg.ua", "kaffeeschluerfer.com",
			"kaffeeschluerfer.de", "kaffeeschluerfer.info", "kaffeeschluerfer.net", "kaffeeschluerfer.org",
			"kaffeeschluerfer.xyz", "kakadua.com", "kasmail.com", "kaspop.com", "kauf.tv", "keg-party.com",
			"keinhirn.de", "keipino.de", "kennedy808.com", "kiani.com", "killmail.com", "killmail.net", "killmail.org",
			"killmail.xyz", "killmailing.com", "killmailing.net", "killmailing.org", "killmailing.xyz", "killspam.com",
			"killspam.net", "killspam.org", "killspam.xyz", "killspamming.com", "killspamming.net", "killspamming.org",
			"killspamming.xyz", "kimsdisk.com", "kingsq.ga", "kingsq.gq", "kingsq.ml", "kingsq.tk", "kiois.com",
			"kitnastar.com", "klzlk.com", "knol-power.nl", "kobrandly.com", "kommespaeter.de", "kon42.com",
			"konsul.xyz", "kook.ml", "kopagas.com", "kopaka.net", "kostenlosemailadresse.de", "koszmail.pl", "krop.kz",
			"krypton.tk", "kundenserver.de", "kurzepost.de", "l2gv.com", "l2gv.net", "l2gv.org", "l2gv.xyz",
			"l2gv.com.br", "l2gv.net.br", "l2gv.org.br", "l2gv.xyz.br", "l2gv.info", "l2gv.ru", "l2gv.su", "l2gv.ua",
			"lackmail.net", "lackmail.ru", "lageri.com", "lags.us", "lalala.fun", "lalala.xyz", "landmail.co",
			"lazyinbox.com", "lazyinbox.net", "lazyinbox.org", "lazyinbox.xyz", "lazyinbox.com.br", "lazyinbox.net.br",
			"lazyinbox.org.br", "lazyinbox.xyz.br", "lazyinbox.info", "lazyinbox.ru", "lazyinbox.su", "lazyinbox.ua",
			"leemail.me", "lellno.com", "lellno.net", "lellno.org", "lellno.xyz", "lellno.com.br", "lellno.net.br",
			"lellno.org.br", "lellno.xyz.br", "lellno.info", "lellno.ru", "lellno.su", "lellno.ua",
			"letmeinonthis.com", "letthemeatspam.com", "lhsdv.com", "lifebyfood.com", "ligsb.com", "link2mail.net",
			"litedrop.com", "liveradio.tk", "llogin.ru", "loadby.us", "login-email.cf", "login-email.ga",
			"login-email.gq", "login-email.ml", "login-email.tk", "loginemail.cf", "loginemail.ga", "loginemail.gq",
			"loginemail.ml", "loginemail.tk", "loh.pp.ua", "lol.ovpn.to", "lolfreak.net", "lookugly.com",
			"lortemail.dk", "louisvuittonbagoutlet.com", "lovemeleaveme.com", "lowly.dk", "lpthe.com", "lrsotv.com",
			"lsz.co.il", "lte.dk", "lucas-imb.de", "lukemail.info", "lutu.org", "luv2.us", "lvie.com", "lyfestyle.com",
			"m4ilweb.info", "macbox.com", "macfreak.com", "macmail.com", "madcreas.com", "madeinindia.com",
			"madonna.com", "magicbox.ro", "magspam.net", "mail.by", "mail.co.ua", "mail.et", "mail.eu", "mail.fr",
			"mail.gr", "mail.hu", "mail.ie", "mail.it", "mail.lt", "mail.lv", "mail.md", "mail.nl", "mail.no",
			"mail.pl", "mail.pt", "mail.ro", "mail.se", "mail.si", "mail.sk", "mail.uk", "mail.us", "mail.xyz",
			"mail1a.de", "mail1web.de", "mail21.cc", "mail2consultant.com", "mail2consultant.net",
			"mail2consultant.org", "mail2consultant.xyz", "mail2world.com", "mail2world.net", "mail2world.org",
			"mail2world.xyz", "mail333.com", "mail4trash.com", "mail7.io", "mail8.com", "mailandnews.com",
			"mailbox.as", "mailbox.co.za", "mailbox.gr", "mailbox.hu", "mailbox72.de", "mailbox80.de", "mailbox82.de",
			"mailbox83.de", "mailbox84.de", "mailbox85.de", "mailbox86.de", "mailbox87.de", "mailbox88.de",
			"mailbox89.de", "mailbox90.de", "mailbox91.de", "mailbox92.de", "mailbox93.de", "mailbox94.de",
			"mailbox95.de", "mailbox96.de", "mailbox97.de", "mailbox98.de", "mailbox99.de", "mailcatch.com",
			"mailchop.com", "mailcker.com", "maildrop.com", "maildrop.net", "maildrop.org", "maildrop.xyz",
			"maildu.de", "maildx.com", "maileater.com", "mailed.ro", "maileimer.de", "mailexpire.com", "mailfa.tk",
			"mailfall.com", "mailfilter.it", "mailfix.net", "mailfly.com", "mailfree.ga", "mailfree.gq", "mailfree.ml",
			"mailfree.tk", "mailfreeonline.com", "mailfreeway.com", "mailfs.com", "mailgates.com", "mailgenie.net",
			"mailguard.me", "mailhood.com", "mailimate.com", "mailin8r.com", "mailinatar.com", "mailinator.net",
			"mailinator.org", "mailinator.xyz", "mailinator2.com", "mailinator2.net", "mailinator2.org",
			"mailinator2.xyz", "mailinator3.com", "mailinator3.net", "mailinator3.org", "mailinator3.xyz",
			"mailinator4.com", "mailinator4.net", "mailinator4.org", "mailinator4.xyz", "mailinator5.com",
			"mailinator5.net", "mailinator5.org", "mailinator5.xyz", "mailinbox.net", "mailingweb.com",
			"mailisent.com", "mailismagic.com", "mailmate.com", "mailme.ir", "mailme.lv", "mailme24.com", "mailmij.nl",
			"mailnesia.com", "mailnull.com", "mailorg.org", "mailowl.com", "mailpanda.com", "mailpickle.com",
			"mailpill.com", "mailpkg.com", "mailplug.com", "mailpost.zzn.com", "mailpride.com", "mailprodigy.com",
			"mailprofs.com", "mailquack.com", "mailrock.biz", "mailsac.com", "mailscrap.com", "mailsend.com",
			"mailshiv.com", "mailsiphon.com", "mailslapping.com", "mailslite.com", "mailstick.com", "mailstored.com",
			"mailstream.net", "mailstrom.com", "mailthrow.com", "mailto.plus", "mailtothis.com", "mailtrash.net",
			"mailtrix.net", "mailtv.net", "mailtv.tv", "mailueberfall.de", "mailwall.com", "mailwatch.com",
			"mailwee.com", "mailwork.cf", "mailwork.ga", "mailwork.gq", "mailwork.ml", "mailwork.tk", "mailzilla.com",
			"mailzilla.org", "mailzilla.xyz", "makemetheking.com", "manifestgenerator.com", "manybrain.com", "mbx.cc",
			"mcache.net", "mciek.com", "mcrb.co.uk", "mdz.email", "meantinc.com", "mega.zik.dj", "mehrani.com",
			"meinspamschutz.de", "meltmail.com", "meltmail.net", "meltmail.org", "meltmail.xyz", "meltmailing.com",
			"meltmailing.net", "meltmailing.org", "meltmailing.xyz", "meltspam.com", "meltspam.net", "meltspam.org",
			"meltspam.xyz", "meltspamming.com", "meltspamming.net", "meltspamming.org", "meltspamming.xyz",
			"memecode.com", "merry.pet", "messagebeamer.de", "mettamail.com", "mezimages.net", "mfsa.info", "mh2o.net",
			"mh2o.org", "mh2o.xyz", "mh2o.com.br", "mh2o.net.br", "mh2o.org.br", "mh2o.xyz.br", "mh2o.info", "mh2o.ru",
			"mh2o.su", "mh2o.ua", "miarroba.com", "midiharmonica.com", "midlertidig.com", "midlertidig.net",
			"midlertidig.org", "midlertidig.xyz", "midlertidig.com.br", "midlertidig.net.br", "midlertidig.org.br",
			"midlertidig.xyz.br", "midlertidig.info", "midlertidig.ru", "midlertidig.su", "midlertidig.ua",
			"midlertidigemail.com", "midlertidigemail.net", "midlertidigemail.org", "midlertidigemail.xyz",
			"midlertidigemail.com.br", "midlertidigemail.net.br", "midlertidigemail.org.br", "midlertidigemail.xyz.br",
			"midlertidigemail.info", "midlertidigemail.ru", "midlertidigemail.su", "midlertidigemail.ua",
			"midlertidigemailing.com", "midlertidigemailing.net", "midlertidigemailing.org", "midlertidigemailing.xyz",
			"midlertidigemailing.com.br", "midlertidigemailing.net.br", "midlertidigemailing.org.br",
			"midlertidigemailing.xyz.br", "midlertidigemailing.info", "midlertidigemailing.ru",
			"midlertidigemailing.su", "midlertidigemailing.ua", "midlertidigespam.com", "midlertidigespam.net",
			"midlertidigespam.org", "midlertidigespam.xyz", "midlertidigespam.com.br", "midlertidigespam.net.br",
			"midlertidigespam.org.br", "midlertidigespam.xyz.br", "midlertidigespam.info", "midlertidigespam.ru",
			"midlertidigespam.su", "midlertidigespam.ua", "mighty.co.za", "migmail.net", "migmail.pl", "migumail.com",
			"mihaus.com", "mijnmail.nl", "mijnstreek.nl", "milliondollarinternet.com", "mini-mail.com",
			"miniature.xyz", "minimail.eu", "minimail.in", "minimail.us", "minimail.xyz", "minimailz.com",
			"minimailz.net", "minimailz.org", "minimailz.xyz", "minimailz.com.br", "minimailz.net.br",
			"minimailz.org.br", "minimailz.xyz.br", "minimailz.info", "minimailz.ru", "minimailz.su", "minimailz.ua",
			"miraclemail.com", "mirrorrr.com", "misterpinball.de", "mji.ro", "mkpfilm.com", "ml2.net", "ml3.net",
			"ml4.net", "ml5.net", "ml6.net", "ml7.net", "ml8.net", "ml9.net", "mnsmail.com", "moakt.cc", "moakt.co",
			"moakt.com", "moakt.net", "moakt.org", "moakt.xyz", "moaktmail.com", "moaktmail.net", "moaktmail.org",
			"moaktmail.xyz", "mobileninja.co.uk", "mochamail.com", "modemnet.net", "modomail.com", "moeinmail.com",
			"moeri.org", "mohmal.com", "mohmal.in", "mohmal.net", "mohmal.org", "mohmal.xyz", "mohmalmail.com",
			"mohmalmail.net", "mohmalmail.org", "mohmalmail.xyz", "moldova.cc", "moldova.net", "moldova.org",
			"moldova.xyz", "moldova.com.br", "moldova.net.br", "moldova.org.br", "moldova.xyz.br", "moldova.info",
			"moldova.ru", "moldova.su", "moldova.ua", "momentics.ru", "moncourrier.fr.nf", "monemail.fr.nf",
			"monemail.net", "monemail.org", "monemail.xyz", "monemailing.com", "monemailing.net", "monemailing.org",
			"monemailing.xyz", "monmail.fr.nf", "monmail.net", "monmail.org", "monmail.xyz", "monmailing.com",
			"monmailing.net", "monmailing.org", "monmailing.xyz", "monoik.com", "monumentmail.com", "moonwake.com",
			"moot.es", "moreawesomethanyou.com", "moreorcs.com", "morsin.com", "moscowmail.ru", "mostlysunny.com",
			"motique.de", "mountainregionallibrary.net", "mox.pp.ua", "mp.uz", "mrblacklist.gq", "mrblacklist.ml",
			"mrblacklist.tk", "mrblacklist.xyz", "mrblacklist.com.br", "mrblacklist.net.br", "mrblacklist.org.br",
			"mrblacklist.xyz.br", "mrblacklist.info", "mrblacklist.ru", "mrblacklist.su", "mrblacklist.ua", "mrch.com",
			"mrvousa.com", "msgden.com", "msgdrop.com", "msgsafe.net", "msgsafe.org", "msgsafe.xyz", "msgsafe.com.br",
			"msgsafe.net.br", "msgsafe.org.br", "msgsafe.xyz.br", "msgsafe.info", "msgsafe.ru", "msgsafe.su",
			"msgsafe.ua", "msgsafeemail.com", "msgsafeemail.net", "msgsafeemail.org", "msgsafeemail.xyz",
			"msgsafeemail.com.br", "msgsafeemail.net.br", "msgsafeemail.org.br", "msgsafeemail.xyz.br",
			"msgsafeemail.info", "msgsafeemail.ru", "msgsafeemail.su", "msgsafeemail.ua", "msgsafeemailing.com",
			"msgsafeemailing.net", "msgsafeemailing.org", "msgsafeemailing.xyz", "msgsafeemailing.com.br",
			"msgsafeemailing.net.br", "msgsafeemailing.org.br", "msgsafeemailing.xyz.br", "msgsafeemailing.info",
			"msgsafeemailing.ru", "msgsafeemailing.su", "msgsafeemailing.ua", "msgspam.com", "msgspam.net",
			"msgspam.org", "msgspam.xyz", "msgspam.com.br", "msgspam.net.br", "msgspam.org.br", "msgspam.xyz.br",
			"msgspam.info", "msgspam.ru", "msgspam.su", "msgspam.ua", "msgspamming.com", "msgspamming.net",
			"msgspamming.org", "msgspamming.xyz", "msgspamming.com.br", "msgspamming.net.br", "msgspamming.org.br",
			"msgspamming.xyz.br", "msgspamming.info", "msgspamming.ru", "msgspamming.su", "msgspamming.ua"
	);

	/**
	 * Provedores conhecidos: consultados <strong>antes</strong> da lista de descartáveis e sempre
	 * vencedores (comparação exata do domínio).
	 *
	 * <p>A lista existia, mas nunca era consultada — e quatro domínios dela (mail.com, mail.ru,
	 * mail.ua e gmx.fr) também estavam na lista de descartáveis, o que recusava clientes desses
	 * provedores. Imutável: nenhum método a altera.</p>
	 */
	private static final Set<String> ALLOWED_DOMAINS = Set.copyOf(List.of("gmail.com", "yahoo.com",
			"hotmail.com", "outlook.com", "live.com", "icloud.com", "aol.com", "protonmail.com", "proton.me",
			"mail.com", "yandex.com", "yandex.ru", "rambler.ru", "mail.ru", "bk.ru", "list.ru", "inbox.ru",
			"internet.ru", "pochta.ru", "mail.ua", "ukr.net", "i.ua", "meta.ua", "bigmir.net", "euroweb.ua",
			"online.ua", "email.ua", "ua.fm", "bfgmail.com", "gmx.com", "gmx.net", "gmx.de", "web.de", "t-online.de",
			"freenet.de", "arcor.de", "gmx.at", "gmx.ch", "bluewin.ch", "hispeed.ch", "sunrise.ch", "gmx.fr", "gmx.es",
			"gmx.it", "libero.it", "tiscali.it", "virgilio.it", "alice.it", "tin.it", "fastwebnet.it", "iol.it",
			"katamail.com", "email.it", "pec.it", "tele2.it", "vodafone.it"));

	private EmailFormatter() {
		throw new UnsupportedOperationException("Classe utilitária não pode ser instanciada");
	}

	// ==================== VALIDAÇÃO PRINCIPAL ====================

	/**
	 * Valida se um e-mail é NORMAL: formato válido e domínio que não é temporário/descartável.
	 *
	 * <p>É a validação recomendada para cadastro de usuários reais. Espaços nas pontas são
	 * ignorados; domínios da lista de permitidos nunca são considerados descartáveis; o domínio e
	 * seus domínios pai são comparados com a lista de descartáveis (ver
	 * {@link #isDisposableDomain(String)}).</p>
	 *
	 * @param email endereço de e-mail a validar
	 * @return {@code true} se for um e-mail normal válido, {@code false} caso contrário
	 */
	public static boolean isValidNormal(String email) {
		String domain = getDomain(email);
		return domain != null && !isDisposableDomain(domain);
	}

	/**
	 * Valida apenas o formato do e-mail (não verifica se é temporário).
	 *
	 * <p>As regras estão na documentação da classe. Entrada nula, vazia ou com mais de 254
	 * caracteres devolve {@code false} sem outra análise, e a análise é linear e sem recursão:
	 * qualquer tamanho de entrada é seguro.</p>
	 *
	 * @param email endereço de e-mail
	 * @return {@code true} se o formato for válido, {@code false} caso contrário
	 */
	public static boolean isValidFormat(String email) {
		String candidate = trimToCandidate(email);
		return candidate != null && hasValidSyntax(candidate, LOCAL_PART_SYMBOLS);
	}

	/**
	 * Valida o formato com a parte local restrita a letras, dígitos, {@code + - _} e pontos.
	 *
	 * <p>Mais conservadora que {@link #isValidFormat(String)}: recusa endereços válidos pela RFC,
	 * porém raros (ex: {@code o'brien@...}). O limite total é de 254 caracteres — era 320, acima do
	 * que um servidor SMTP aceita.</p>
	 *
	 * @param email endereço de e-mail
	 * @return {@code true} se o formato for estritamente válido, {@code false} caso contrário
	 */
	public static boolean isValidStrict(String email) {
		String candidate = trimToCandidate(email);
		return candidate != null && hasValidSyntax(candidate, STRICT_LOCAL_PART_SYMBOLS);
	}

	/**
	 * Verifica se um domínio é temporário/descartável.
	 *
	 * <p>Espaços nas pontas, maiúsculas e um ponto final ({@code "mailinator.com."}) são ignorados.
	 * Domínio da lista de permitidos devolve {@code false} sempre. Fora isso, o domínio e cada
	 * domínio pai são procurados na lista: {@code "a.b.mailinator.com"} é descartável porque
	 * {@code "mailinator.com"} é. Antes a comparação era exata, e qualquer subdomínio de um serviço
	 * descartável passava.</p>
	 *
	 * <p>Texto com mais de 253 caracteres não é um domínio e devolve {@code false} (a validação de
	 * formato já o recusa).</p>
	 *
	 * @param domain domínio do e-mail (ex: {@code "mailinator.com"})
	 * @return {@code true} se for temporário/descartável, {@code false} caso contrário
	 */
	public static boolean isDisposableDomain(String domain) {
		String normalized = normalizeDomain(domain);
		if (normalized == null || ALLOWED_DOMAINS.contains(normalized)) {
			return false;
		}
		String candidate = normalized;
		while (true) {
			if (DISPOSABLE_DOMAINS.contains(candidate)) {
				return true;
			}
			int dot = candidate.indexOf('.');
			if (dot < 0) {
				return false;
			}
			candidate = candidate.substring(dot + 1);
		}
	}

	/**
	 * Verifica se um domínio está na lista de provedores conhecidos (permitidos).
	 *
	 * <p>Comparação exata, sem considerar espaços nas pontas, maiúsculas nem ponto final.</p>
	 *
	 * @param domain domínio do e-mail
	 * @return {@code true} se estiver na lista de permitidos, {@code false} caso contrário
	 */
	public static boolean isAllowedDomain(String domain) {
		String normalized = normalizeDomain(domain);
		return normalized != null && ALLOWED_DOMAINS.contains(normalized);
	}

	// ==================== NORMALIZAÇÃO E FORMATAÇÃO ====================

	/**
	 * Normaliza um e-mail: remove espaços nas pontas e converte para minúsculas.
	 *
	 * <p>A conversão usa {@link Locale#ROOT}: com o locale padrão da JVM em turco,
	 * {@code "ADMIN"} virava {@code "admın"} (i sem ponto), e um domínio descartável escrito em
	 * maiúsculas passava pela checagem.</p>
	 *
	 * @param email endereço de e-mail
	 * @return e-mail normalizado ou {@code null} se a entrada for nula
	 */
	public static String normalize(String email) {
		if (email == null) {
			return null;
		}
		return email.trim().toLowerCase(Locale.ROOT);
	}

	/**
	 * Formata um e-mail com nome para exibição (ex: {@code "Nome <email@dominio.com>"}).
	 *
	 * <p>O nome é saneado para que o resultado também sirva como <strong>um único</strong>
	 * destinatário: caracteres de controle e quebras de linha viram espaço (uma quebra de linha no
	 * nome permitia injetar cabeçalhos), e um nome com caractere especial da RFC 5322
	 * ({@code , ; : < > @ ( ) [ ] . "} ou barra invertida) sai entre aspas, com aspas e barras
	 * invertidas escapadas. Sem isso, {@code format("Silva, João", ...)} produzia um texto que os
	 * programas de e-mail leem como <em>dois</em> destinatários. Nomes comuns saem como antes, sem
	 * aspas.</p>
	 *
	 * <p>O e-mail é apenas normalizado ({@link #normalize(String)}), não validado: para usar o
	 * resultado como destinatário, valide o e-mail antes com {@link #isValidFormat(String)} ou
	 * {@link #isValidNormal(String)}.</p>
	 *
	 * @param name  nome do destinatário (pode ser {@code null} ou vazio)
	 * @param email endereço de e-mail
	 * @return texto formatado; apenas o e-mail normalizado se o nome for vazio; {@code null} se o
	 *         e-mail for {@code null}
	 */
	public static String format(String name, String email) {
		if (email == null) {
			return null;
		}
		String address = normalize(email);
		String displayName = toSafeDisplayName(name);
		return displayName.isEmpty() ? address : displayName + " <" + address + ">";
	}

	/**
	 * Extrai a parte local do e-mail (antes do {@code @}), sem os espaços das pontas.
	 *
	 * @param email endereço de e-mail
	 * @return parte local do e-mail ou {@code null} se o formato for inválido
	 */
	public static String getLocalPart(String email) {
		if (!isValidFormat(email)) {
			return null;
		}
		String candidate = email.trim();
		return candidate.substring(0, candidate.indexOf('@'));
	}

	/**
	 * Extrai o domínio do e-mail (depois do {@code @}), em minúsculas e sem os espaços das pontas.
	 *
	 * @param email endereço de e-mail
	 * @return domínio do e-mail ou {@code null} se o formato for inválido
	 */
	public static String getDomain(String email) {
		if (!isValidFormat(email)) {
			return null;
		}
		String candidate = email.trim();
		return candidate.substring(candidate.indexOf('@') + 1).toLowerCase(Locale.ROOT);
	}

	/**
	 * Mascara um e-mail para exibição segura (ex: {@code "us***@dominio.com"}).
	 *
	 * @param email endereço de e-mail
	 * @return e-mail mascarado ou {@code null} se o formato for inválido
	 */
	public static String mask(String email) {
		String local = getLocalPart(email);
		String domain = getDomain(email);
		if (local == null || domain == null) {
			return null;
		}
		if (local.length() <= 2) {
			return "***@" + domain;
		}
		return local.substring(0, 2) + "***@" + domain;
	}

	/**
	 * Mascara um e-mail exibindo apenas o primeiro caractere e o domínio.
	 *
	 * @param email endereço de e-mail
	 * @return e-mail mascarado (ex: {@code "j***@exemplo.com"}) ou {@code null} se o formato for
	 *         inválido
	 */
	public static String maskWithFirstChar(String email) {
		String local = getLocalPart(email);
		String domain = getDomain(email);
		if (local == null || domain == null) {
			return null;
		}
		if (local.isEmpty()) {
			return "***@" + domain;
		}
		return local.substring(0, 1) + "***@" + domain;
	}

	// ==================== VALIDAÇÃO DE MÚLTIPLOS E-MAILS ====================

	/**
	 * Filtra uma lista de e-mails, devolvendo apenas os normais (válidos e não temporários), já
	 * normalizados.
	 *
	 * @param emails lista de e-mails
	 * @return nova lista contendo apenas os e-mails normais
	 */
	public static List<String> filterNormal(List<String> emails) {
		if (emails == null) {
			return Collections.emptyList();
		}
		return emails.stream().filter(EmailFormatter::isValidNormal).map(EmailFormatter::normalize)
				.collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
	}

	/**
	 * Verifica se todos os e-mails de uma lista são normais.
	 *
	 * @param emails lista de e-mails
	 * @return {@code true} se todos forem normais; {@code false} se algum não for ou se a lista
	 *         for nula ou vazia
	 */
	public static boolean areAllNormal(List<String> emails) {
		if (emails == null || emails.isEmpty()) {
			return false;
		}
		return emails.stream().allMatch(EmailFormatter::isValidNormal);
	}

	// ==================== UTILITÁRIOS ADICIONAIS ====================

	/**
	 * Adiciona um domínio à lista de domínios descartáveis (vale também para os subdomínios).
	 *
	 * <p>Seguro para chamar com a aplicação no ar. Domínios da lista de permitidos continuam aceitos
	 * mesmo se adicionados aqui.</p>
	 *
	 * @param domain domínio a bloquear (ex: {@code "tempemail.com"})
	 */
	public static void addDisposableDomain(String domain) {
		String normalized = normalizeDomain(domain);
		if (normalized != null) {
			DISPOSABLE_DOMAINS.add(normalized);
		}
	}

	/**
	 * Remove um domínio da lista de domínios descartáveis.
	 *
	 * <p>Seguro para chamar com a aplicação no ar.</p>
	 *
	 * @param domain domínio a remover
	 */
	public static void removeDisposableDomain(String domain) {
		String normalized = normalizeDomain(domain);
		if (normalized != null) {
			DISPOSABLE_DOMAINS.remove(normalized);
		}
	}

	/**
	 * Gera um e-mail aleatório para testes, no formato {@code "test_XXXXXXXX@example.com"}.
	 *
	 * <p>O endereço tem formato válido e é <strong>aceito</strong> por
	 * {@link #isValidNormal(String)}: {@code example.com} não é descartável (a documentação antiga
	 * dizia o contrário). O domínio é reservado para exemplos (RFC 2606) e não recebe e-mail de
	 * verdade — serve para preencher cadastros em teste, não para testar envio.</p>
	 *
	 * @return e-mail aleatório em {@code example.com}
	 */
	public static String generateRandomEmail() {
		String randomPart = UUID.randomUUID().toString().substring(0, 8);
		return "test_" + randomPart + "@example.com";
	}

	/**
	 * Gera um e-mail normal aleatório, com um domínio da lista de permitidos.
	 *
	 * @return e-mail aleatório em domínio permitido (ex: {@code gmail.com})
	 */
	public static String generateNormalRandomEmail() {
		String[] allowedArray = ALLOWED_DOMAINS.toArray(new String[0]);
		String randomDomain = allowedArray[new Random().nextInt(allowedArray.length)];
		String randomPart = UUID.randomUUID().toString().substring(0, 8);
		return "user_" + randomPart + "@" + randomDomain;
	}

	/**
	 * Verifica se dois e-mails são iguais, ignorando maiúsculas/minúsculas e espaços nas pontas.
	 *
	 * @param email1 primeiro e-mail
	 * @param email2 segundo e-mail
	 * @return {@code true} se forem iguais após {@link #normalize(String)} (ou ambos nulos)
	 */
	public static boolean equalsIgnoreCase(String email1, String email2) {
		if (email1 == null && email2 == null) {
			return true;
		}
		if (email1 == null || email2 == null) {
			return false;
		}
		return normalize(email1).equals(normalize(email2));
	}

	// ==================== MENSAGENS DE ERRO ====================

	/**
	 * Devolve uma mensagem de erro, em português, pronta para exibir ao usuário.
	 *
	 * @param email e-mail a verificar
	 * @return mensagem de erro, ou {@code null} se o e-mail for normal
	 */
	public static String getValidationErrorMessage(String email) {
		if (email == null || email.trim().isEmpty()) {
			return "O e-mail não pode estar vazio.";
		}
		if (!isValidFormat(email)) {
			return "Formato de e-mail inválido.";
		}
		if (isDisposableDomain(getDomain(email))) {
			return "E-mail temporário não é permitido. Use um e-mail permanente.";
		}
		return null;
	}

	// ==================== IMPLEMENTAÇÃO ====================

	/**
	 * Devolve o e-mail sem os espaços das pontas, ou {@code null} se ficar vazio ou passar de 254
	 * caracteres. É a primeira coisa que toda validação faz: nada examina um texto maior que isso.
	 */
	private static String trimToCandidate(String email) {
		if (email == null) {
			return null;
		}
		String trimmed = email.trim();
		return trimmed.isEmpty() || trimmed.length() > MAX_ADDRESS_LENGTH ? null : trimmed;
	}

	/**
	 * Analisa {@code parte-local@domínio} numa varredura linear, sem expressão regular.
	 *
	 * <p>A expressão regular antiga repetia um grupo por rótulo de domínio, e o
	 * {@code java.util.regex} empilha uma chamada recursiva por repetição de grupo: um endereço com
	 * alguns milhares de rótulos estourava a pilha da thread da requisição. Aqui não há recursão, e
	 * o texto já chega limitado a 254 caracteres.</p>
	 *
	 * @param address       endereço já aparado e dentro do limite de tamanho
	 * @param localSymbols  símbolos aceitos na parte local, além de letras e dígitos ASCII
	 */
	private static boolean hasValidSyntax(String address, String localSymbols) {
		int at = address.indexOf('@');
		if (at <= 0 || at > MAX_LOCAL_PART_LENGTH || at != address.lastIndexOf('@')) {
			return false;
		}
		return isValidLocalPart(address, at, localSymbols) && isValidDomain(address, at + 1);
	}

	/** Parte local {@code [0, end)}: blocos não vazios separados por um único ponto. */
	private static boolean isValidLocalPart(String address, int end, String symbols) {
		boolean expectingAtom = true;
		for (int i = 0; i < end; i++) {
			char c = address.charAt(i);
			if (c == '.') {
				if (expectingAtom) {
					return false; // ponto no início ou dois pontos seguidos
				}
				expectingAtom = true;
			} else if (isAsciiLetterOrDigit(c) || symbols.indexOf(c) >= 0) {
				expectingAtom = false;
			} else {
				return false;
			}
		}
		return !expectingAtom; // não pode terminar em ponto
	}

	/**
	 * Domínio a partir de {@code start}: dois ou mais rótulos de 1 a 63 caracteres (letras, dígitos e
	 * hífen, sem hífen nas pontas) e domínio de topo válido.
	 */
	private static boolean isValidDomain(String address, int start) {
		int end = address.length();
		int labelStart = start;
		int labels = 0;
		for (int i = start; i <= end; i++) {
			if (i < end && address.charAt(i) != '.') {
				char c = address.charAt(i);
				if (!isAsciiLetterOrDigit(c) && c != '-') {
					return false;
				}
				continue;
			}
			int length = i - labelStart;
			if (length == 0 || length > MAX_LABEL_LENGTH) {
				return false;
			}
			if (address.charAt(labelStart) == '-' || address.charAt(i - 1) == '-') {
				return false;
			}
			labels++;
			labelStart = i + 1;
		}
		return labels >= 2 && isValidTopLevelLabel(address, address.lastIndexOf('.') + 1, end);
	}

	/** Domínio de topo: duas letras ou mais, ou IDN em punycode ({@code xn--p1ai}, por exemplo). */
	private static boolean isValidTopLevelLabel(String address, int start, int end) {
		int length = end - start;
		if (length < 2) {
			return false;
		}
		if (length > 4 && address.regionMatches(true, start, "xn--", 0, 4)) {
			return true; // os caracteres do rótulo já foram conferidos em isValidDomain
		}
		for (int i = start; i < end; i++) {
			if (!isAsciiLetter(address.charAt(i))) {
				return false;
			}
		}
		return true;
	}

	private static boolean isAsciiLetter(char c) {
		return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
	}

	private static boolean isAsciiLetterOrDigit(char c) {
		return isAsciiLetter(c) || (c >= '0' && c <= '9');
	}

	/**
	 * Domínio sem espaços nas pontas, em minúsculas ({@link Locale#ROOT}) e sem ponto final; ou
	 * {@code null} se ficar vazio ou longo demais para ser um domínio.
	 */
	private static String normalizeDomain(String domain) {
		if (domain == null) {
			return null;
		}
		String trimmed = domain.trim();
		if (trimmed.isEmpty() || trimmed.length() > MAX_DOMAIN_LENGTH + 1) {
			return null;
		}
		String normalized = trimmed.toLowerCase(Locale.ROOT);
		if (normalized.endsWith(".")) {
			normalized = normalized.substring(0, normalized.length() - 1);
		}
		return normalized.isEmpty() ? null : normalized;
	}

	/**
	 * Nome de exibição seguro para um cabeçalho de destinatário: controles viram espaço e nomes com
	 * caractere especial saem entre aspas (ver {@link #format(String, String)}).
	 */
	private static String toSafeDisplayName(String name) {
		if (name == null) {
			return "";
		}
		StringBuilder cleaned = new StringBuilder(name.length());
		for (int i = 0; i < name.length(); i++) {
			char c = name.charAt(i);
			cleaned.append(isControlOrLineBreak(c) ? ' ' : c);
		}
		String trimmed = cleaned.toString().trim();
		boolean needsQuotes = false;
		for (int i = 0; i < trimmed.length() && !needsQuotes; i++) {
			needsQuotes = DISPLAY_NAME_SPECIALS.indexOf(trimmed.charAt(i)) >= 0;
		}
		if (!needsQuotes) {
			return trimmed;
		}
		StringBuilder quoted = new StringBuilder(trimmed.length() + 8).append('"');
		for (int i = 0; i < trimmed.length(); i++) {
			char c = trimmed.charAt(i);
			if (c == '"' || c == '\\') {
				quoted.append('\\');
			}
			quoted.append(c);
		}
		return quoted.append('"').toString();
	}

	/** Caractere de controle (CR, LF, TAB, NUL...) ou separador de linha/parágrafo Unicode. */
	private static boolean isControlOrLineBreak(char c) {
		return Character.isISOControl(c) || c == '\u2028' || c == '\u2029';
	}

	/** Conjunto concorrente com os valores dados (repetições são ignoradas). */
	private static Set<String> concurrentSetOf(String... values) {
		Set<String> set = ConcurrentHashMap.newKeySet(values.length * 2);
		Collections.addAll(set, values);
		return set;
	}
}
