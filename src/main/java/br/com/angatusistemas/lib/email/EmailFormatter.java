package br.com.angatusistemas.lib.email;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * [PT] Classe utilitária para formatação e validação de endereços de e-mail.
 * <p>
 * Fornece validação rigorosa seguindo a RFC 5322 (com adaptações para uso
 * prático), normalização, extração de domínio, formatação de nome + e-mail, e
 * <b>rejeição automática de e-mails temporários/descartáveis</b>.
 * </p>
 * <p>
 * <b>Exemplos de uso:</b>
 * 
 * <pre>
 * // Validação normal (rejeita e-mails temporários)
 * boolean valido = EmailFormatter.isValidNormal("usuario@gmail.com"); // true
 * boolean invalido = EmailFormatter.isValidNormal("teste@mailinator.com"); // false
 *
 * // Formatação para exibição
 * String formatado = EmailFormatter.format("Usuário", "usuario@exemplo.com");
 *
 * // Extração de domínio
 * String dominio = EmailFormatter.getDomain("usuario@exemplo.com");
 * </pre>
 * </p>
 *
 * [EN] Utility class for formatting and validating email addresses.
 * <p>
 * Provides strict validation following RFC 5322 (with practical adaptations),
 * normalization, domain extraction, name+email formatting, and <b>automatic
 * rejection of temporary/disposable emails</b>.
 * </p>
 * <p>
 * <b>Usage examples:</b>
 * 
 * <pre>
 * // Normal validation (rejects temporary emails)
 * boolean valid = EmailFormatter.isValidNormal("user@gmail.com"); // true
 * boolean invalid = EmailFormatter.isValidNormal("test@mailinator.com"); // false
 *
 * // Format for display
 * String formatted = EmailFormatter.format("User", "user@example.com");
 *
 * // Domain extraction
 * String domain = EmailFormatter.getDomain("user@example.com");
 * </pre>
 * </p>
 *
 * @author Angatu Sistemas
 * @see <a href="https://tools.ietf.org/html/rfc5322">RFC 5322</a>
 */
public final class EmailFormatter {

	// Regex para validação de e-mail (RFC 5322 adaptado para uso prático)
	private static final String EMAIL_REGEX = "^[a-zA-Z0-9_+&*-]+(?:\\.[a-zA-Z0-9_+&*-]+)*@(?:[a-zA-Z0-9-]+\\.)+[a-zA-Z]{2,}$";
	private static final Pattern EMAIL_PATTERN = Pattern.compile(EMAIL_REGEX, Pattern.CASE_INSENSITIVE);

	// Regex para validação mais rigorosa (com suporte a caracteres especiais)
	private static final String STRICT_EMAIL_REGEX = "^(?=.{1,64}@)[A-Za-z0-9\\+_\\-]+(\\.[A-Za-z0-9\\+_\\-]+)*@[A-Za-z0-9\\-]+(\\.[A-Za-z0-9\\-]+)*(\\.[A-Za-z]{2,})$";
	private static final Pattern STRICT_EMAIL_PATTERN = Pattern.compile(STRICT_EMAIL_REGEX, Pattern.CASE_INSENSITIVE);

	// Domínios de e-mail temporário/descartável (bloqueados)
	private static final Set<String> DISPOSABLE_DOMAINS = new HashSet<>(Arrays.asList(
			// Gerais
			"tempmail.com", "10minutemail.com", "guerrillamail.com", "mailinator.com", "yopmail.com", "throwaway.email",
			"sharklasers.com", "guerrillamail.net", "guerrillamail.org", "guerrillamail.biz", "mailmetrash.com",
			"trashmail.com", "temp-mail.org", "tempmail.net", "tempinbox.com", "fakeinbox.com", "getnada.com",
			"mailnator.com", "dispostable.com", "spambox.us", "mintemail.com", "mytrashmail.com", "trash2009.com",
			"trashdevil.com", "trashymail.com", "tyldd.com", "uggsrock.com", "wegwerfmail.de", "wegwerfmail.net",
			"wegwerfmail.org", "wh4f.org", "whyspam.me", "willselfdestruct.com", "winemaven.info", "wronghead.com",
			"wuzup.net", "xagloo.com", "xemaps.com", "xents.com", "xmaily.com", "xoxy.net", "yep.it", "yogamaven.com",
			"yopmail.fr", "yopmail.net", "ypmail.webarnak.fr.eu.org",

			// Adicionais
			"maildrop.cc", "guerrillamail.biz", "guerrillamail.org", "guerrillamail.net", "guerrillamail.com",
			"spam4.me", "spamspot.com", "tempemail.net", "tempmail.net", "temp-mail.net", "temp-mail.org",
			"tempinbox.co", "tempinbox.com", "tempomail.fr", "temporarily.de", "temporario.email", "temporary-mail.net",
			"temporaryemail.net", "temporayemail.net", "temporry.com", "temporry.net", "temporry.org",
			"temporryemail.com", "temporryemail.net", "temporryemail.org", "temporrymail.com", "temporrymail.net",
			"temporrymail.org", "temporrymail.co", "10minut.xyz", "10minute.com", "10minutemail.co.za",
			"10minutemail.com", "10minutemail.net", "10minutemail.org", "10minutemail.us", "10minutemail.xyz",
			"10minutemailz.com", "10minutesmail.com", "10minutesmail.net", "10minutesmail.org", "10minutesmail.us",
			"10minutesmail.xyz", "1secmail.com", "1secmail.net", "1secmail.org", "20minutemail.com", "20minutemail.it",
			"20minutemail.net", "2prong.com", "30minutemail.com", "30minutemail.org", "33mail.com", "3d-painting.com",
			"3mail.fi", "4warding.com", "4warding.net", "4warding.org", "5mail.xyz", "60minutemail.com", "6mail.xyz",
			"7mail.xyz", "8mail.xyz", "9mail.xyz", "abyssmail.com", "acmemail.net", "aeneasmail.com", "airmail.net",
			"airmailhub.com", "airpost.net", "allmail.net", "altmails.com", "amail.com", "amail4.me", "amail.club",
			"amail.to", "amail.xyz", "anonymbox.com", "antichef.com", "antichef.net", "antireg.com", "antireg.ru",
			"antispam.de", "antispam24.de", "antispammail.de", "armyspy.com", "artman-mail.com", "artman-mail.de",
			"artman-mail.net", "artman-mail.org", "averdov.com", "averdov.net", "averdov.org", "averdov.su",
			"averdov.xyz", "azazazatashkent.tk", "baxomale.ht.cx", "beefmilk.com", "bigstring.com", "binkmail.com",
			"bizmail.net", "bobmail.info", "bobmurch.com", "bofthew.com", "bongobongo.ga", "bongobongo.gq",
			"bongobongo.ml", "bongobongo.tk", "brefmail.com", "brennendesreich.de", "broadbandninja.com", "bsnow.net",
			"buon.club", "burnthespam.info", "burstmail.info", "busiwebs.com", "buygoldmail.com", "c2.hu", "c2.li",
			"c2.lv", "c2.si", "c2.vc", "cachedot.net", "card.zp.ua", "casualdx.com", "cbair.com", "cechire.com",
			"cek.pm", "cellurl.com", "centermail.com", "centermail.net", "chammy.info", "cheatmail.de", "chogmail.com",
			"choicemail1.com", "clixser.com", "cmail.club", "cmail.com", "cmail.net", "cmail.org", "coldemail.info",
			"cool.fr.nf", "courriel.fr.nf", "courrieltemporaire.com", "crapmail.org", "curryworld.de", "cust.in",
			"d3p.dk", "dacoolest.com", "dandikmail.com", "dayrep.com", "dcemail.com", "deadaddress.com", "deadspam.com",
			"deagot.com", "dealja.com", "despam.it", "despammed.com", "devnullmail.com", "dfgh.net",
			"digitalsanctuary.com", "dingbone.com", "discard.email", "discardmail.com", "discardmail.de",
			"disposableaddress.com", "disposableemail.com", "disposableemail.org", "disposableinbox.com", "dispose.it",
			"disposeamail.com", "disposemail.com", "dispostable.com", "divermail.com", "dm.w3sites.net", "dodgeit.com",
			"dodgit.com", "dodgit.org", "doiea.com", "dolphinnet.net", "donebyng.com", "dotman.de", "dotmsg.com",
			"drdrb.com", "drdrb.net", "drdrb.org", "drdrb.ru", "drdrb.su", "drdrb.xyz", "drdrba.com", "drdrba.net",
			"drdrba.org", "drdrba.ru", "drdrba.su", "drdrba.xyz", "drdrbb.com", "drdrbb.net", "drdrbb.org", "drdrbb.ru",
			"drdrbb.su", "drdrbb.xyz", "drdrbc.com", "drdrbc.net", "drdrbc.org", "drdrbc.ru", "drdrbc.su", "drdrbc.xyz",
			"drdrbd.com", "drdrbd.net", "drdrbd.org", "drdrbd.ru", "drdrbd.su", "drdrbd.xyz", "drdrbe.com",
			"drdrbe.net", "drdrbe.org", "drdrbe.ru", "drdrbe.su", "drdrbe.xyz", "dropmail.me", "dt.com", "duam.net",
			"dudmail.com", "dump-email.info", "dumpandjunk.com", "dumpmail.de", "dumpyemail.com", "dwjworld.com",
			"e-mail.com", "e-mail.org", "e4ward.com", "easytrashmail.com", "einrot.com", "einrot.de", "eintagsmail.de",
			"email60.com", "emailacc.com", "emailage.ga", "emailage.gq", "emailage.ml", "emailage.tk", "emaildienst.de",
			"emailfake.com", "emailforyou.net", "emailgo.de", "emailias.com", "emailigo.com", "emailinfive.com",
			"emailisvalid.com", "emaillime.com", "emailmiser.com", "emailproxsy.com", "emailresort.com", "emails.ga",
			"emails.gq", "emails.ml", "emails.tk", "emailsense.com", "emailspam.cf", "emailspam.ga", "emailspam.gq",
			"emailspam.ml", "emailspam.tk", "emailsubject.com", "emailtemporario.com", "emailtemporario.net",
			"emailtemporario.org", "emailtemporario.xyz", "emailtemporario.com.br", "emailtemporario.net.br",
			"emailtemporario.org.br", "emailtemporario.xyz.br", "emailtmp.com", "emailto.org", "emailwarden.com",
			"emailx.at", "emailx.net", "emailx.org", "emailx.xyz", "emailxfer.com", "emailz.ga", "emailz.gq",
			"emailz.ml", "emailz.tk", "eml.cc", "eml.pp.ua", "emlhub.com", "emlpro.com", "emltmp.com", "empireanime.ga",
			"empireanime.gq", "empireanime.ml", "empireanime.tk", "emz.net", "enterto.com", "ephemail.net",
			"ero-tube.org", "etranquil.com", "etranquil.net", "etranquil.org", "evopo.com", "explodemail.com",
			"express.net.ua", "eyepaste.com", "f4k.es", "f5.si", "facebook-email.ga", "facebook-email.gq",
			"facebook-email.ml", "facebook-email.tk", "facebookmail.ga", "facebookmail.gq", "facebookmail.ml",
			"facebookmail.tk", "facebookmailer.ga", "facebookmailer.gq", "facebookmailer.ml", "facebookmailer.tk",
			"fake-email.pp.ua", "fake-mail.cf", "fake-mail.ga", "fake-mail.gq", "fake-mail.ml", "fake-mail.tk",
			"fakebox.ga", "fakebox.gq", "fakebox.ml", "fakebox.tk", "fakeemail.de", "fakeinbox.cf", "fakeinbox.ga",
			"fakeinbox.gq", "fakeinbox.ml", "fakeinbox.tk", "fakemail.fr", "fakemail.net", "fakemail.org",
			"fakemail.xyz", "fakemailgenerator.com", "fakemailz.com", "fammix.com", "fansworldwide.de",
			"fantasymail.de", "fastacura.com", "fastchevy.com", "fastchrysler.com", "fastcruz.com", "fastdodge.com",
			"fastemail.us", "fastholden.com", "fasthonda.com", "fasthummer.com", "fastinfiniti.com", "fastjaguar.com",
			"fastjeep.com", "fastkia.com", "fastlamborghini.com", "fastlandrover.com", "fastlexus.com", "fastmazda.com",
			"fastmitsubishi.com", "fastnissan.com", "fastpontiac.com", "fastporsche.com", "fastsaab.com",
			"fastsaturn.com", "fastscion.com", "fastsubaru.com", "fastsuzuki.com", "fasttoyota.com",
			"fastvolkswagen.com", "fastvolvo.com", "fauxmail.com", "femail.ga", "femail.gq", "femail.ml", "femail.tk",
			"ficken.de", "figshot.com", "fiifke.com", "filzmail.com", "fivemail.de", "fixmail.tk", "fizmail.com",
			"flashbox.5v.pl", "fleckens.hu", "fliegend.com", "flurred.com", "fly-ts.de", "flyspam.com", "foobar.com",
			"forgetmail.com", "fr33mail.info", "frapmail.com", "free-email.ga", "free-email.gq", "free-email.ml",
			"free-email.tk", "freecoolemail.com", "freefattymovies.com", "freemail.bo.pl", "freemail.c3.cx",
			"freemail.ms", "freemail.xxx", "freemails.ga", "freemails.gq", "freemails.ml", "freemails.tk",
			"freemeil.ga", "freemeil.gq", "freemeil.ml", "freemeil.tk", "freerubik.ru", "freeschoolgirls.net",
			"freesmail.net", "freeweb.email", "freeweb.org", "freeyellow.com", "friendlymail.net", "front14.org",
			"ftp.sh", "fullmail.com", "funkymail.de", "fux0ringduh.com", "fw.mn", "garbagemail.org", "gardenscape.ca",
			"garliclife.com", "gatamail.com", "gaumesnil.com", "gehensiemirnichtaufdensack.de", "gelitik.in",
			"get1mail.com", "get2mail.fr", "getairmail.com", "getcloudmail.com", "getmails.eu", "getonemail.com",
			"getonemail.net", "getsimpleemail.com", "gett.ee", "ghosttexter.de", "giantmail.de", "ginzi.be",
			"ginzi.co.uk", "ginzi.es", "ginzi.eu", "ginzi.fr", "ginzi.it", "ginzi.net", "ginzi.org", "ginzi.xyz",
			"girlmail.ws", "girlsindetention.com", "gishpuppy.com", "givehimthefinger.info", "givememail.club",
			"gmx.fr", "goat.si", "google-mail.ga", "google-mail.gq", "google-mail.ml", "google-mail.tk",
			"googlemail.ga", "googlemail.gq", "googlemail.ml", "googlemail.tk", "googlegroups.ga", "googlegroups.gq",
			"googlegroups.ml", "googlegroups.tk", "gorillaswithdirtyarmpits.com", "gotmail.com", "gotmail.net",
			"gotmail.org", "gowikitest.com", "grafischeweb.de", "grandmamail.com", "grandmasmail.com", "great-host.in",
			"greensloth.com", "grr.la", "gs-arc.org", "gsredcross.org", "gsrv.co.uk", "guerillamail.biz",
			"guerillamail.com", "guerillamail.net", "guerillamail.org", "guerrillamail.biz", "guerrillamail.com",
			"guerrillamail.net", "guerrillamail.org", "guerrillamailblock.com", "gustr.com", "h.mintemail.com",
			"h8s.org", "h9s.org", "hablas.com", "haltospam.com", "harakirimail.com", "hartbot.de", "hat-geld.de",
			"hatespam.org", "hawrong.com", "haydoo.com", "hazelnutbread.com", "hecat.es", "hellodream.mobi",
			"hellokitty.com", "helmsen.net", "herp.in", "hidemail.de", "hidzz.com", "hmamail.com", "hochsitzungen.de",
			"hoer.pw", "holl.ga", "holl.gq", "holl.ml", "holl.tk", "hopemail.biz", "hotpop.com", "hulapla.de",
			"humn.ws.gd", "hush.ai", "hush.com", "hushmail.com", "hushmail.me", "hushmail.net", "hushmail.org",
			"hushmail.xyz", "hushmailing.com", "hushmailings.com", "hushmailings.net", "hushmailings.org",
			"hushmailings.xyz", "hushmailings.com.br", "hushmailings.net.br", "hushmailings.org.br",
			"hushmailings.xyz.br", "hushmailings.info", "hushmailings.ru", "hushmailings.su", "hushmailings.ua",
			"i2pmail.org", "i6.cloudns.cc", "i6.cloudns.cf", "i6.cloudns.ga", "i6.cloudns.gq", "i6.cloudns.ml",
			"i6.cloudns.tk", "i6.cloudns.xyz", "i6.xyz", "iaoss.com", "ibm.net", "icantbelieveineedtoexplainthis.com",
			"icemail.com", "ichigo.me", "ieatspam.eu", "ieatspam.info", "ieh-mail.de", "ihateyoualot.info",
			"ihatespam.com", "ihatespam.info", "ihatespam.net", "ihatespam.org", "ihatespam.xyz", "ihatespamming.com",
			"ihatespamming.net", "ihatespamming.org", "ihatespamming.xyz", "iheartspam.com", "iheartspam.net",
			"iheartspam.org", "iheartspam.xyz", "iheartspamming.com", "iheartspamming.net", "iheartspamming.org",
			"iheartspamming.xyz", "iheartspammy.com", "iheartspammy.net", "iheartspammy.org", "iheartspammy.xyz",
			"ikbenspamvrij.nl", "ilovespam.com", "ilovespam.net", "ilovespam.org", "ilovespam.xyz", "ilovespamming.com",
			"ilovespamming.net", "ilovespamming.org", "ilovespamming.xyz", "imails.info", "imgof.com", "imgv.de",
			"immo-gerance.info", "imstations.com", "inbax.tk", "inbox.si", "inboxalias.com", "inboxbear.com",
			"inboxclean.com", "inboxclean.org", "inboxdesign.com", "inboxed.pw", "inboxkitten.com", "inboxmail.eu",
			"inboxme.eu", "inboxproxy.com", "inboxstore.me", "incognitomail.com", "incognitomail.net",
			"incognitomail.org", "incognitomail.xyz", "incognitomail.com.br", "incognitomail.net.br",
			"incognitomail.org.br", "incognitomail.xyz.br", "ineec.net", "inerted.com", "infocom.zp.ua", "inggo.org",
			"inoutbox.com", "insanum.xyz", "insorg-mail.info", "instaddr.com", "instantemailaddress.com",
			"instantmail.fr", "instantmailaddress.com", "ipoo.org", "irish2k.com", "iwi.net", "jajxz.com",
			"jdmadventures.com", "jellyfishpink.net", "jetable.com", "jetable.fr.nf", "jetable.net", "jetable.org",
			"jetable.pp.ua", "jetableemail.com", "jetablemail.com", "jmail.ovh", "jmail.ro", "jmailr.com", "jmailz.com",
			"job.cf", "job.ga", "job.gq", "job.ml", "job.tk", "junk1.com", "junkmail.com", "junkmail.ga", "junkmail.gq",
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
			"kitnastar.com", "klicksafe.de", "klzlk.com", "knol-power.nl", "kobrandly.com", "kommespaeter.de",
			"kon42.com", "konsul.xyz", "kook.ml", "kopagas.com", "kopaka.net", "kostenlosemailadresse.de",
			"koszmail.pl", "krop.kz", "krypton.tk", "kundenserver.de", "kurzepost.de", "l2gv.com", "l2gv.net",
			"l2gv.org", "l2gv.xyz", "l2gv.com.br", "l2gv.net.br", "l2gv.org.br", "l2gv.xyz.br", "l2gv.info", "l2gv.ru",
			"l2gv.su", "l2gv.ua", "lackmail.net", "lackmail.ru", "lageri.com", "lags.us", "lalala.fun", "lalala.xyz",
			"landmail.co", "lazyinbox.com", "lazyinbox.net", "lazyinbox.org", "lazyinbox.xyz", "lazyinbox.com.br",
			"lazyinbox.net.br", "lazyinbox.org.br", "lazyinbox.xyz.br", "lazyinbox.info", "lazyinbox.ru",
			"lazyinbox.su", "lazyinbox.ua", "leemail.me", "lellno.com", "lellno.net", "lellno.org", "lellno.xyz",
			"lellno.com.br", "lellno.net.br", "lellno.org.br", "lellno.xyz.br", "lellno.info", "lellno.ru", "lellno.su",
			"lellno.ua", "letmeinonthis.com", "letthemeatspam.com", "lhsdv.com", "lifebyfood.com", "ligsb.com",
			"link2mail.net", "litedrop.com", "liveradio.tk", "llogin.ru", "loadby.us", "login-email.cf",
			"login-email.ga", "login-email.gq", "login-email.ml", "login-email.tk", "loginemail.cf", "loginemail.ga",
			"loginemail.gq", "loginemail.ml", "loginemail.tk", "loh.pp.ua", "lol.ovpn.to", "lolfreak.net",
			"lookugly.com", "lortemail.dk", "louisvuittonbagoutlet.com", "lovemeleaveme.com", "lowly.dk", "lpthe.com",
			"lrsotv.com", "lsz.co.il", "lte.dk", "lucas-imb.de", "lukemail.info", "lutu.org", "luv2.us", "lvie.com",
			"lyfestyle.com", "lycos.com", "lycos.de", "lycos.es", "lycos.it", "lycos.net", "lycos.org", "lycos.ru",
			"lycos.ua", "lycos.xyz", "lycosmail.com", "lycosmail.net", "lycosmail.org", "lycosmail.xyz", "m4ilweb.info",
			"mac.hush.com", "macbox.com", "macfreak.com", "macmail.com", "madcreas.com", "madeinindia.com",
			"madonna.com", "magicbox.ro", "magspam.net", "mail.by", "mail.co.ua", "mail.com", "mail.de", "mail.ee",
			"mail.et", "mail.eu", "mail.fr", "mail.gr", "mail.hu", "mail.ie", "mail.it", "mail.lt", "mail.lv",
			"mail.md", "mail.nl", "mail.no", "mail.pl", "mail.pt", "mail.ro", "mail.ru", "mail.se", "mail.si",
			"mail.sk", "mail.ua", "mail.uk", "mail.us", "mail.xyz", "mail1a.de", "mail1web.de", "mail21.cc",
			"mail2consultant.com", "mail2consultant.net", "mail2consultant.org", "mail2consultant.xyz",
			"mail2world.com", "mail2world.net", "mail2world.org", "mail2world.xyz", "mail333.com", "mail4trash.com",
			"mail7.io", "mail8.com", "mailandftp.com", "mailandnews.com", "mailbox.as", "mailbox.co.za", "mailbox.gr",
			"mailbox.hu", "mailbox72.de", "mailbox80.de", "mailbox82.de", "mailbox83.de", "mailbox84.de",
			"mailbox85.de", "mailbox86.de", "mailbox87.de", "mailbox88.de", "mailbox89.de", "mailbox90.de",
			"mailbox91.de", "mailbox92.de", "mailbox93.de", "mailbox94.de", "mailbox95.de", "mailbox96.de",
			"mailbox97.de", "mailbox98.de", "mailbox99.de", "mailcatch.com", "mailchop.com", "mailcker.com",
			"maildrop.cc", "maildrop.com", "maildrop.net", "maildrop.org", "maildrop.xyz", "maildu.de", "maildx.com",
			"maileater.com", "mailed.ro", "maileimer.de", "mailexpire.com", "mailfa.tk", "mailfall.com",
			"mailfence.com", "mailfilter.it", "mailfix.net", "mailfly.com", "mailfree.ga", "mailfree.gq", "mailfree.ml",
			"mailfree.tk", "mailfreeonline.com", "mailfreeway.com", "mailfs.com", "mailftp.com", "mailgates.com",
			"mailgenie.net", "mailguard.me", "mailhaven.com", "mailhood.com", "mailimate.com", "mailin8r.com",
			"mailinatar.com", "mailinator.com", "mailinator.net", "mailinator.org", "mailinator.xyz", "mailinator2.com",
			"mailinator2.net", "mailinator2.org", "mailinator2.xyz", "mailinator3.com", "mailinator3.net",
			"mailinator3.org", "mailinator3.xyz", "mailinator4.com", "mailinator4.net", "mailinator4.org",
			"mailinator4.xyz", "mailinator5.com", "mailinator5.net", "mailinator5.org", "mailinator5.xyz",
			"mailinblack.com", "mailinblack.net", "mailinblack.org", "mailinblack.xyz", "mailinbox.net",
			"mailingaddress.org", "mailingweb.com", "mailisent.com", "mailismagic.com", "mailite.com", "mailmate.com",
			"mailme.ir", "mailme.lv", "mailme24.com", "mailmetrash.com", "mailmight.com", "mailmij.nl", "mailnator.com",
			"mailnesia.com", "mailnew.com", "mailnull.com", "mailorg.org", "mailowl.com", "mailpanda.com",
			"mailpickle.com", "mailpill.com", "mailpkg.com", "mailplug.com", "mailpost.zzn.com", "mailpride.com",
			"mailprodigy.com", "mailprofs.com", "mailquack.com", "mailrock.biz", "mailsac.com", "mailscrap.com",
			"mailsend.com", "mailservice.ms", "mailshiv.com", "mailsiphon.com", "mailslapping.com", "mailslite.com",
			"mailstick.com", "mailstored.com", "mailstream.net", "mailstrom.com", "mailthrow.com", "mailto.plus",
			"mailtothis.com", "mailtrash.net", "mailtrix.net", "mailtv.net", "mailtv.tv", "mailueberfall.de",
			"mailup.net", "mailwall.com", "mailwatch.com", "mailwee.com", "mailwork.cf", "mailwork.ga", "mailwork.gq",
			"mailwork.ml", "mailwork.tk", "mailzilla.com", "mailzilla.org", "mailzilla.xyz", "makemetheking.com",
			"manifestgenerator.com", "manybrain.com", "mbx.cc", "mcache.net", "mciek.com", "mcrb.co.uk", "mdz.email",
			"meantinc.com", "mega.zik.dj", "mehrani.com", "meinspamschutz.de", "meltmail.com", "meltmail.net",
			"meltmail.org", "meltmail.xyz", "meltmailing.com", "meltmailing.net", "meltmailing.org", "meltmailing.xyz",
			"meltspam.com", "meltspam.net", "meltspam.org", "meltspam.xyz", "meltspamming.com", "meltspamming.net",
			"meltspamming.org", "meltspamming.xyz", "memecode.com", "merry.pet", "messagebeamer.de", "mettamail.com",
			"mexicomail.com", "mezimages.net", "mfsa.info", "mh2o.net", "mh2o.org", "mh2o.xyz", "mh2o.com.br",
			"mh2o.net.br", "mh2o.org.br", "mh2o.xyz.br", "mh2o.info", "mh2o.ru", "mh2o.su", "mh2o.ua", "miarroba.com",
			"microfocus.com", "microsoft.com", "midiharmonica.com", "midlertidig.com", "midlertidig.net",
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
			"mihaus.com", "mijnhva.nl", "mijnmail.nl", "mijnstreek.nl", "mikrotik.com", "mikrotik.net", "mikrotik.org",
			"mikrotik.xyz", "mikrotik.com.br", "mikrotik.net.br", "mikrotik.org.br", "mikrotik.xyz.br", "mikrotik.info",
			"mikrotik.ru", "mikrotik.su", "mikrotik.ua", "milliondollarinternet.com", "mini-mail.com", "miniature.xyz",
			"minimail.eu", "minimail.in", "minimail.us", "minimail.xyz", "minimailz.com", "minimailz.net",
			"minimailz.org", "minimailz.xyz", "minimailz.com.br", "minimailz.net.br", "minimailz.org.br",
			"minimailz.xyz.br", "minimailz.info", "minimailz.ru", "minimailz.su", "minimailz.ua", "minister.com",
			"mintemail.com", "miraclemail.com", "mirrorrr.com", "misterpinball.de", "mji.ro", "mkpfilm.com", "ml1.net",
			"ml2.net", "ml3.net", "ml4.net", "ml5.net", "ml6.net", "ml7.net", "ml8.net", "ml9.net", "mm.st", "mnsi.net",
			"mnsmail.com", "moakt.cc", "moakt.co", "moakt.com", "moakt.net", "moakt.org", "moakt.xyz", "moaktmail.com",
			"moaktmail.net", "moaktmail.org", "moaktmail.xyz", "mobileninja.co.uk", "mochamail.com", "modemnet.net",
			"modomail.com", "moeinmail.com", "moeri.org", "mohmal.com", "mohmal.in", "mohmal.net", "mohmal.org",
			"mohmal.xyz", "mohmalmail.com", "mohmalmail.net", "mohmalmail.org", "mohmalmail.xyz", "moldova.cc",
			"moldova.net", "moldova.org", "moldova.xyz", "moldova.com.br", "moldova.net.br", "moldova.org.br",
			"moldova.xyz.br", "moldova.info", "moldova.ru", "moldova.su", "moldova.ua", "momentics.ru",
			"moncourrier.fr.nf", "monemail.fr.nf", "monemail.net", "monemail.org", "monemail.xyz", "monemailing.com",
			"monemailing.net", "monemailing.org", "monemailing.xyz", "monmail.fr.nf", "monmail.net", "monmail.org",
			"monmail.xyz", "monmailing.com", "monmailing.net", "monmailing.org", "monmailing.xyz", "monoik.com",
			"monumentmail.com", "moonwake.com", "moot.es", "moreawesomethanyou.com", "moreorcs.com", "morsin.com",
			"moscowmail.ru", "mostlysunny.com", "motique.de", "mountainregionallibrary.net", "mox.pp.ua", "mp.uz",
			"mrblacklist.gq", "mrblacklist.ml", "mrblacklist.tk", "mrblacklist.xyz", "mrblacklist.com.br",
			"mrblacklist.net.br", "mrblacklist.org.br", "mrblacklist.xyz.br", "mrblacklist.info", "mrblacklist.ru",
			"mrblacklist.su", "mrblacklist.ua", "mrch.com", "mrvousa.com", "msgden.com", "msgdrop.com", "msgsafe.io",
			"msgsafe.net", "msgsafe.org", "msgsafe.xyz", "msgsafe.com.br", "msgsafe.net.br", "msgsafe.org.br",
			"msgsafe.xyz.br", "msgsafe.info", "msgsafe.ru", "msgsafe.su", "msgsafe.ua", "msgsafeemail.com",
			"msgsafeemail.net", "msgsafeemail.org", "msgsafeemail.xyz", "msgsafeemail.com.br", "msgsafeemail.net.br",
			"msgsafeemail.org.br", "msgsafeemail.xyz.br", "msgsafeemail.info", "msgsafeemail.ru", "msgsafeemail.su",
			"msgsafeemail.ua", "msgsafeemailing.com", "msgsafeemailing.net", "msgsafeemailing.org",
			"msgsafeemailing.xyz", "msgsafeemailing.com.br", "msgsafeemailing.net.br", "msgsafeemailing.org.br",
			"msgsafeemailing.xyz.br", "msgsafeemailing.info", "msgsafeemailing.ru", "msgsafeemailing.su",
			"msgsafeemailing.ua", "msgspam.com", "msgspam.net", "msgspam.org", "msgspam.xyz", "msgspam.com.br",
			"msgspam.net.br", "msgspam.org.br", "msgspam.xyz.br", "msgspam.info", "msgspam.ru", "msgspam.su",
			"msgspam.ua", "msgspamming.com", "msgspamming.net", "msgspamming.org", "msgspamming.xyz",
			"msgspamming.com.br", "msgspamming.net.br", "msgspamming.org.br", "msgspamming.xyz.br", "msgspamming.info",
			"msgspamming.ru", "msgspamming.su", "msgspamming.ua", "msgsafe.io", "msgsafe.net", "msgsafe.org",
			"msgsafe.xyz", "msgsafe.com.br", "msgsafe.net.br", "msgsafe.org.br", "msgsafe.xyz.br", "msgsafe.info",
			"msgsafe.ru", "msgsafe.su", "msgsafe.ua", "msgsafeemail.com", "msgsafeemail.net", "msgsafeemail.org",
			"msgsafeemail.xyz", "msgsafeemail.com.br", "msgsafeemail.net.br", "msgsafeemail.org.br",
			"msgsafeemail.xyz.br", "msgsafeemail.info", "msgsafeemail.ru", "msgsafeemail.su", "msgsafeemail.ua",
			"msgsafeemailing.com", "msgsafeemailing.net", "msgsafeemailing.org", "msgsafeemailing.xyz",
			"msgsafeemailing.com.br", "msgsafeemailing.net.br", "msgsafeemailing.org.br", "msgsafeemailing.xyz.br",
			"msgsafeemailing.info", "msgsafeemailing.ru", "msgsafeemailing.su", "msgsafeemailing.ua", "msgspam.com",
			"msgspam.net", "msgspam.org", "msgspam.xyz", "msgspam.com.br", "msgspam.net.br", "msgspam.org.br",
			"msgspam.xyz.br", "msgspam.info", "msgspam.ru", "msgspam.su", "msgspam.ua", "msgspamming.com",
			"msgspamming.net", "msgspamming.org", "msgspamming.xyz", "msgspamming.com.br", "msgspamming.net.br",
			"msgspamming.org.br", "msgspamming.xyz.br", "msgspamming.info", "msgspamming.ru", "msgspamming.su",
			"msgspamming.ua"));

	// Domínios de e-mail válidos/normais (exceções de segurança)
	private static final Set<String> ALLOWED_DOMAINS = new HashSet<>(Arrays.asList("gmail.com", "yahoo.com",
			"hotmail.com", "outlook.com", "live.com", "icloud.com", "aol.com", "protonmail.com", "proton.me",
			"mail.com", "yandex.com", "yandex.ru", "rambler.ru", "mail.ru", "bk.ru", "list.ru", "inbox.ru",
			"internet.ru", "pochta.ru", "mail.ua", "ukr.net", "i.ua", "meta.ua", "bigmir.net", "euroweb.ua",
			"online.ua", "email.ua", "ua.fm", "bfgmail.com", "gmx.com", "gmx.net", "gmx.de", "web.de", "t-online.de",
			"freenet.de", "arcor.de", "gmx.at", "gmx.ch", "bluewin.ch", "hispeed.ch", "sunrise.ch", "gmx.fr", "gmx.es",
			"gmx.it", "libero.it", "tiscali.it", "virgilio.it", "alice.it", "tin.it", "fastwebnet.it", "iol.it",
			"katamail.com", "email.it", "pec.it", "tele2.it", "vodafone.it"));

	private EmailFormatter() {
		throw new UnsupportedOperationException("Utility class cannot be instantiated");
	}

	// ==================== VALIDAÇÃO PRINCIPAL ====================

	/**
	 * [PT] Valida se um e-mail é NORMAL (formato válido + NÃO é
	 * temporário/descartável).
	 * <p>
	 * Esta é a validação recomendada para cadastros de usuários reais.
	 * </p>
	 *
	 * [EN] Validates if an email is NORMAL (valid format + NOT
	 * temporary/disposable).
	 * <p>
	 * This is the recommended validation for real user registrations.
	 * </p>
	 *
	 * @param email [PT] endereço de e-mail a ser validado [EN] email address to
	 *              validate
	 * @return [PT] true se for um e-mail normal válido, false caso contrário [EN]
	 *         true if it's a valid normal email, false otherwise
	 */
	public static boolean isValidNormal(String email) {
		if (!isValidFormat(email)) {
			return false;
		}
		String domain = getDomain(email);
		if (domain == null) {
			return false;
		}
		return !isDisposableDomain(domain);
	}

	/**
	 * [PT] Valida apenas o formato do e-mail (não verifica se é temporário).
	 *
	 * [EN] Validates only the email format (does not check if it's temporary).
	 *
	 * @param email [PT] endereço de e-mail [EN] email address
	 * @return [PT] true se o formato for válido, false caso contrário [EN] true if
	 *         format is valid, false otherwise
	 */
	public static boolean isValidFormat(String email) {
		if (email == null || email.trim().isEmpty()) {
			return false;
		}
		return EMAIL_PATTERN.matcher(email.trim()).matches();
	}

	/**
	 * [PT] Valida se um e-mail é válido usando validação rigorosa.
	 *
	 * [EN] Validates if an email is valid using strict validation.
	 *
	 * @param email [PT] endereço de e-mail [EN] email address
	 * @return [PT] true se o formato for estritamente válido, false caso contrário
	 *         [EN] true if format is strictly valid, false otherwise
	 */
	public static boolean isValidStrict(String email) {
		if (email == null || email.trim().isEmpty()) {
			return false;
		}
		String trimmed = email.trim();
		if (trimmed.length() > 320) {
			return false;
		}
		return STRICT_EMAIL_PATTERN.matcher(trimmed).matches();
	}

	/**
	 * [PT] Verifica se um domínio é temporário/descartável.
	 *
	 * [EN] Checks if a domain is temporary/disposable.
	 *
	 * @param domain [PT] domínio do e-mail [EN] email domain
	 * @return [PT] true se for temporário/descartável, false caso contrário [EN]
	 *         true if temporary/disposable, false otherwise
	 */
	public static boolean isDisposableDomain(String domain) {
		if (domain == null)
			return false;
		return DISPOSABLE_DOMAINS.contains(domain.toLowerCase());
	}

	/**
	 * [PT] Verifica se um domínio é permitido (lista de domínios confiáveis).
	 *
	 * [EN] Checks if a domain is allowed (list of trusted domains).
	 *
	 * @param domain [PT] domínio do e-mail [EN] email domain
	 * @return [PT] true se estiver na lista de permitidos, false caso contrário
	 *         [EN] true if in allowed list, false otherwise
	 */
	public static boolean isAllowedDomain(String domain) {
		if (domain == null)
			return false;
		return ALLOWED_DOMAINS.contains(domain.toLowerCase());
	}

	// ==================== NORMALIZAÇÃO E FORMATAÇÃO ====================

	/**
	 * [PT] Normaliza um e-mail (remove espaços, converte para minúsculas).
	 *
	 * [EN] Normalizes an email (removes spaces, converts to lowercase).
	 *
	 * @param email [PT] endereço de e-mail [EN] email address
	 * @return [PT] e-mail normalizado ou null se entrada for nula [EN] normalized
	 *         email or null if input is null
	 */
	public static String normalize(String email) {
		if (email == null)
			return null;
		return email.trim().toLowerCase();
	}

	/**
	 * [PT] Formata um e-mail com nome para exibição (ex: "Nome
	 * <email@dominio.com>").
	 *
	 * [EN] Formats an email with name for display (e.g., "Name
	 * <email@domain.com>").
	 *
	 * @param nome  [PT] nome do destinatário [EN] recipient name
	 * @param email [PT] endereço de e-mail [EN] email address
	 * @return [PT] string formatada ou apenas o e-mail se nome for inválido [EN]
	 *         formatted string or just the email if name is invalid
	 */
	public static String format(String nome, String email) {
		if (email == null)
			return null;
		if (nome == null || nome.trim().isEmpty()) {
			return normalize(email);
		}
		return nome.trim() + " <" + normalize(email) + ">";
	}

	/**
	 * [PT] Extrai a parte local do e-mail (antes do @).
	 *
	 * [EN] Extracts the local part of the email (before @).
	 *
	 * @param email [PT] endereço de e-mail [EN] email address
	 * @return [PT] parte local do e-mail ou null se inválido [EN] local part of the
	 *         email or null if invalid
	 */
	public static String getLocalPart(String email) {
		if (!isValidFormat(email))
			return null;
		int atIndex = email.indexOf('@');
		if (atIndex == -1)
			return null;
		return email.substring(0, atIndex);
	}

	/**
	 * [PT] Extrai o domínio do e-mail (depois do @).
	 *
	 * [EN] Extracts the domain of the email (after @).
	 *
	 * @param email [PT] endereço de e-mail [EN] email address
	 * @return [PT] domínio do e-mail ou null se inválido [EN] email domain or null
	 *         if invalid
	 */
	public static String getDomain(String email) {
		if (!isValidFormat(email))
			return null;
		int atIndex = email.indexOf('@');
		if (atIndex == -1)
			return null;
		return email.substring(atIndex + 1).toLowerCase();
	}

	/**
	 * [PT] Mascara um e-mail para exibição segura (ex: usu***@dominio.com).
	 *
	 * [EN] Masks an email for safe display (e.g., use***@domain.com).
	 *
	 * @param email [PT] endereço de e-mail [EN] email address
	 * @return [PT] e-mail mascarado ou null se inválido [EN] masked email or null
	 *         if invalid
	 */
	public static String mask(String email) {
		if (!isValidFormat(email))
			return null;
		String local = getLocalPart(email);
		String domain = getDomain(email);
		if (local == null || domain == null)
			return null;

		if (local.length() <= 2) {
			return "***@" + domain;
		}
		String maskedLocal = local.substring(0, 2) + "***";
		return maskedLocal + "@" + domain;
	}

	/**
	 * [PT] Mascara um e-mail exibindo apenas o primeiro caractere e o domínio.
	 *
	 * [EN] Masks an email showing only the first character and the domain.
	 *
	 * @param email [PT] endereço de e-mail [EN] email address
	 * @return [PT] e-mail mascarado (ex: "j***@exemplo.com") [EN] masked email
	 *         (e.g., "j***@example.com")
	 */
	public static String maskWithFirstChar(String email) {
		if (!isValidFormat(email))
			return null;
		String local = getLocalPart(email);
		String domain = getDomain(email);
		if (local == null || domain == null)
			return null;

		if (local.isEmpty())
			return "***@" + domain;
		String maskedLocal = local.substring(0, 1) + "***";
		return maskedLocal + "@" + domain;
	}

	// ==================== VALIDAÇÃO DE MÚLTIPLOS E-MAILS ====================

	/**
	 * [PT] Filtra uma lista de e-mails, retornando apenas os normais (válidos + não
	 * temporários).
	 *
	 * [EN] Filters a list of emails, returning only normal ones (valid + not
	 * temporary).
	 *
	 * @param emails [PT] lista de e-mails [EN] list of emails
	 * @return [PT] lista contendo apenas e-mails normais [EN] list containing only
	 *         normal emails
	 */
	public static List<String> filterNormal(List<String> emails) {
		if (emails == null)
			return Collections.emptyList();
		return emails.stream().filter(EmailFormatter::isValidNormal).map(EmailFormatter::normalize)
				.collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
	}

	/**
	 * [PT] Verifica se todos os e-mails em uma lista são normais.
	 *
	 * [EN] Checks if all emails in a list are normal.
	 *
	 * @param emails [PT] lista de e-mails [EN] list of emails
	 * @return [PT] true se todos forem normais, false caso contrário [EN] true if
	 *         all are normal, false otherwise
	 */
	public static boolean areAllNormal(List<String> emails) {
		if (emails == null || emails.isEmpty())
			return false;
		return emails.stream().allMatch(EmailFormatter::isValidNormal);
	}

	// ==================== UTILITÁRIOS ADICIONAIS ====================

	/**
	 * [PT] Adiciona um domínio à lista de domínios descartáveis.
	 *
	 * [EN] Adds a domain to the list of disposable domains.
	 *
	 * @param domain [PT] domínio a ser bloqueado (ex: "tempemail.com") [EN] domain
	 *               to block (e.g., "tempemail.com")
	 */
	public static void addDisposableDomain(String domain) {
		if (domain != null && !domain.trim().isEmpty()) {
			DISPOSABLE_DOMAINS.add(domain.toLowerCase().trim());
		}
	}

	/**
	 * [PT] Remove um domínio da lista de domínios descartáveis.
	 *
	 * [EN] Removes a domain from the list of disposable domains.
	 *
	 * @param domain [PT] domínio a ser removido [EN] domain to remove
	 */
	public static void removeDisposableDomain(String domain) {
		if (domain != null) {
			DISPOSABLE_DOMAINS.remove(domain.toLowerCase().trim());
		}
	}

	/**
	 * [PT] Gera um e-mail aleatório para testes (nunca será considerado normal).
	 *
	 * [EN] Generates a random email for testing (will never be considered normal).
	 *
	 * @return [PT] e-mail aleatório no formato "test_XXXXX@example.com" [EN] random
	 *         email in format "test_XXXXX@example.com"
	 */
	public static String generateRandomEmail() {
		String randomPart = UUID.randomUUID().toString().substring(0, 8);
		return "test_" + randomPart + "@example.com";
	}

	/**
	 * [PT] Gera um e-mail normal aleatório (com domínio permitido).
	 *
	 * [EN] Generates a random normal email (with allowed domain).
	 *
	 * @return [PT] e-mail aleatório em domínio permitido (ex: gmail.com) [EN]
	 *         random email with allowed domain (e.g., gmail.com)
	 */
	public static String generateNormalRandomEmail() {
		String[] allowedArray = ALLOWED_DOMAINS.toArray(new String[0]);
		String randomDomain = allowedArray[new Random().nextInt(allowedArray.length)];
		String randomPart = UUID.randomUUID().toString().substring(0, 8);
		return "user_" + randomPart + "@" + randomDomain;
	}

	/**
	 * [PT] Verifica se dois e-mails são iguais (ignorando maiúsculas/minúsculas).
	 *
	 * [EN] Checks if two emails are equal (case-insensitive).
	 *
	 * @param email1 [PT] primeiro e-mail [EN] first email
	 * @param email2 [PT] segundo e-mail [EN] second email
	 * @return [PT] true se forem iguais (ignorando case), false caso contrário [EN]
	 *         true if equal (case-insensitive), false otherwise
	 */
	public static boolean equalsIgnoreCase(String email1, String email2) {
		if (email1 == null && email2 == null)
			return true;
		if (email1 == null || email2 == null)
			return false;
		return normalize(email1).equals(normalize(email2));
	}

	// ==================== MENSAGENS DE ERRO ====================

	/**
	 * [PT] Retorna uma mensagem de erro descritiva para um e-mail inválido.
	 *
	 * [EN] Returns a descriptive error message for an invalid email.
	 *
	 * @param email [PT] e-mail a ser verificado [EN] email to check
	 * @return [PT] mensagem de erro ou null se o e-mail for normal [EN] error
	 *         message or null if email is normal
	 */
	public static String getValidationErrorMessage(String email) {
		if (email == null || email.trim().isEmpty()) {
			return "O e-mail não pode estar vazio / Email cannot be empty";
		}
		if (!isValidFormat(email)) {
			return "Formato de e-mail inválido / Invalid email format";
		}
		if (isDisposableDomain(getDomain(email))) {
			return "E-mail temporário não é permitido. Use um e-mail permanente / Temporary email not allowed. Use a permanent email";
		}
		return null;
	}
}