/**
 * Created by razvanc on 02/05/2014.
 */

/** tie a floating checkbox to localStorage
 *
 * inputId and storageName should be same... however, the storageName should have a pageId to avoid conflicts with other pages...
 */
function useLocalStorageCheckbox (inputId, storageName, callback) {
  if(localStorage.getItem(storageName) != null)
    $('#'+inputId).prop('checked', localStorage.getItem(storageName) === 'true');

  function updConfirm() {
    localStorage.setItem(storageName, $('#'+inputId).prop('checked'));

    if(typeof callback != 'undefined')
      callback(inputId, storageName, $('#'+inputId).prop('checked'));
  }

  // call to initialize
  if(typeof callback != 'undefined')
    callback(inputId, storageName, $('#'+inputId).prop('checked'));

  $('#'+inputId).change(updConfirm);
}

//function dontConfirm() {
//  return $('#confirmChanges').prop('checked') ? false : true;
//}


/* http://www.codelib.net/javascript/cookies.html */
/* JKM, cookies, cookies-jkm.js, 1-5-06 */

function reldate(days) {
  var d;
  d = new Date();

  /* We need to add a relative amount of time to
   the current date. The basic unit of JavaScript
   time is milliseconds, so we need to convert the
   days value to ms. Thus we have
   ms/day
   = 1000 ms/sec *  60 sec/min * 60 min/hr * 24 hrs/day
   = 86,400,000. */

  d.setTime(d.getTime() + days*86400000);
  return d.toGMTString();
}

/** returns null or cookie */
function getCookie(name) {
  var s = document.cookie, i;
  if (s)
    for (i=0, s=s.split('; '); i<s.length; i++) {
      s[i] = s[i].split('=', 2);
      if (unescape(s[i][0]) == name)
        return unescape(s[i][1]);
    }
  return null;
}

function setCookie(name, value) {
  makeCookie(name, value, { path : "/" });
}

function makeCookie(name, value, p) {
  var s, k;
  s = escape(name) + '=' + escape(value);
  if (p) for (k in p) {

    /* convert a numeric expires value to a relative date */

    if (k == 'expires')
      p[k] = isNaN(p[k]) ? p[k] : reldate(p[k]);

    /* The secure property is the only special case
     here, and it causes two problems. Rather than
     being '; protocol=secure' like all other
     properties, the secure property is set by
     appending '; secure', so we have to use a
     ternary statement to format the string.

     The second problem is that secure doesn't have
     any value associated with it, so whatever value
     people use doesn't matter. However, we don't
     want to surprise people who set { secure: false }.
     For this reason, we actually do have to check
     the value of the secure property so that someone
     won't end up with a secure cookie when
     they didn't want one. */

    if (p[k])
      s += '; ' + (k != 'secure' ? k + '=' + p[k] : k);
  }
  document.cookie = s;
  return getCookie(name) == value;
}

function rmCookie(name) {
  return !makeCookie(name, '', { expires: -1, path : "/" });
}


///////////////// animations

function weBlink(selector,text,color,duration) {
  var dur = duration || 1000;
  $(selector).text(text);
  $(selector).css("background-color", color);
  $(selector).fadeIn(dur, function() {
    $(selector).fadeOut(dur);
  });
}

///////////////// notes

var pinTags = getCookie("pinTags");

if(pinTags == null) pinTags = "";

function setPinTags (tags) {
  setCookie("pinTags", tags);
  pinTags = getCookie("pinTags");
  location.reload();
}


///////////////// forms

function weGetField(frm,name) {
  if(typeof name == 'undefined')
    return $('[name="'+frm+'"]').val();
  else
    return $('#'+frm+' [name="'+name+'"]').val();
}

function weSetField(frm,name, v) {
  if(typeof v == 'undefined')
    return $('[name="'+frm+'"]').val(name);
  else
    return $('#'+frm+' [name="'+name+'"]').val(v);
}

function weFormEditNote(frm,name) {
  var content = weGetField(name);
  weNoteEditPopup(frm,name, content);
  return false;
}


function weFormEditedNote(frm,name, content) {
  $('#'+frm+' [name="'+name+'-holder"]').text(content);
  weSetField(frm,name, content);

  return false;
}


//========= browser

var weBrowserStorage = "weBrowser";

/** toggle the we browser on and off */
function weToggleBrowser() {
  if(getCookie(weBrowserStorage) == null) setCookie(weBrowserStorage, "false")

  if(getCookie(weBrowserStorage) === "true") {
    setCookie(weBrowserStorage, "false")
  } else {
    setCookie(weBrowserStorage, "true")
  }
  window.location.reload();
}

/** is the we browser on */
function weIsBrowser() {
  if(getCookie(weBrowserStorage) == null) setCookie(weBrowserStorage, "false")

  return getCookie(weBrowserStorage) === "true";
}

//==================== fiddles

/** this works from anywhere, to open the fiddle on an element */
function wefiddle(wpath,line,col) {
  if(wpath.includes("Spec:")) {
    window.location.href='/diesel/fiddle/playDom'+'?line='+line+'&col='+col+'&spec='+wpath
  } else if(wpath.includes("Story:")) {
    window.location.href='/diesel/fiddle/playDom'+'?line='+line+'&col='+col+'&story='+wpath
  }
}

/** add something after page loaded */
function weOnLoad (f) {
  if (window.addEventListener) {
    window.addEventListener('load', f, false);
  } else if (window.attachEvent)
    window.attachEvent('onload', f);
}


//======================== widgets

function weButton(b) {
  var u = $(b).attr('href');
  if(typeof u != 'undefined') window.location=u;
  return false;
}

//====================== widgets <a onclick="weMsg('ctx.log','m='+m)"

/** trigger message in background, log result
* ea should be "pack.entity.action"
*/
function weMsg(ea,p) {
  return iweMsg(ea.replace(/(.+)\.([^.]+)$/, "$1/$2"), p, 'value',
    function(data){console.log(data);});
}

/** trigger message in background, popup result
* ea should be "pack.entity.action"
 */
function weMsgPopup(ea,p) {
  return iweMsg(ea.replace(/(.+)\.([^.]+)$/, "$1/$2"), p, 'value',
    function(data){alert(data);});
}

/** run message in background
 * ea should be "pack.entity/action"
 */
function iweMsg(ea,p,what,succ) {
  var u = '/diesel/fiddle/react/'+ea+'?resultMode='+what+'&'+p
  $.ajax(
    u, {
      type: 'POST',
      data: $.param({
        // xdomEngineConfig: domEngineConfig
      }),
      contentType: 'application/x-www-form-urlencoded',
      success: function (data) {
        if(typeof succ == 'function') succ(data);
      },
      error: function (x) {
        // readyState=4  is failure to parse json reply
        if(x.status == "200" && typeof succ == 'function') succ(x.responseText);
        console.log("ERR " + JSON.stringify(x));
      }
    });
  return false;
}


/** add a bad ip to the list
 */
function weBadIp(ip) {
  var u = '/doe/fiddle/react/'+ea+'?resultMode='+what+'&'+p
  $.ajax(
    u, {
      type: 'POST',
      data: $.param({
        ip : ip
      }),
      contentType: 'application/x-www-form-urlencoded',
      success: function (data) {
      },
      error: function (x) {
        console.log("ERR " + JSON.stringify(x));
      }
    });
  return false;
}

