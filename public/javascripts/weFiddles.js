/**
 * Created by razvanc on 02/05/2014.
 */

var CHECK_TIMER = 1000;

// assume views deal with one engine at a time
var currEngineId = null;
var currEngineData = null;

// the current request or null - if this is something, we won't run new ones
var curDomRequest = null;

var isSlow = false;

// am I waiting for a refresh now? this counts how many
var isRefreshing = 0;
var isRefreshing2 = 0;

var lastStartTime = new Date().getTime();
var lastRequestTime = new Date().getTime();
var lastProcessTime = null;

var storyHasErrors = false;
var specHasErrors = false;

// utils

var razuri = function(s) {
  return encodeURI(s.replace(/;/g,'%3B').replace(/\+/g,'%2B').replace(/script/g,'scrRAZipt'));
};

var encscr = function(s) {
  return s.replace(/'/g,'%27');
};

var decscr = function (s) {
  return s.replace(/%27/g,'\'');
};

/** post to update fiddle on server */
function doRunFiddle (code, url, verb, what, lang, wpath, tags, doSuccess, doError) {
  $.ajax(
    '/sfiddle/runFiddle/'+what+'/'+realm+'?wpath='+wpath, {
      type: 'POST',
      data: $.param({
        l : lang,
        tags : tags,
        u: url,
        v: verb,
        j : code
      }),
      contentType: 'application/x-www-form-urlencoded',
      success: doSuccess,
      error  : doError
    });
};


/** post to update fiddle on server */
function doSaveFiddle (code, what, lang, wpath, tags, doSuccess, doError) {
  $.ajax(
    '/sfiddle/saveFiddle/'+what+'/'+realm+'?wpath='+wpath, {
      type: 'POST',
      data: $.param({
        l : lang,
        tags : tags,
        j : code
      }),
      contentType: 'application/x-www-form-urlencoded',
      success: doSuccess,
      error  : doError
    });
};



/** post to update fiddle on server */
function doUpdateFiddle (code, what, lang, wpath, tags) {
  $.ajax(
    '/sfiddle/updateFiddle/'+what+'?wpath='+wpath, {
      type: 'POST',
      data: $.param({
        l : lang,
        what : what,
        tags : tags,
        j : code
      }),
      contentType: 'application/x-www-form-urlencoded',
      success: function(data) {
        console.log("Fiddle saved " + data);
        var x = wpath;

        if(x == '') x = data;

        if(x.indexOf('Note:') >= 0)
          window.location.replace('/sfiddle/playinbrowser/js?wpath=' + x);
      },
      error  : function(x) {
        console.log( "ERR "+x.toString());
        alert("Fiddle NOT saved - ERROR: "+x.toString());
      }
    });
};


/** Enhanced from http://cwestblog.com/2016/03/16/javascript-detecting-infinite-loops/
 *
 * See
 *
 * @param code
 * @param fnOnStop
 * @param opt_timeoutInMS
 * @param output
 */
function limitEval(code, fnOnStop, opt_timeoutInMS, output) {
  var id = Math.random() + 1;

  var script = 'onmessage=function(a){' +
  'output="";'+
  'preambleData=a.data;' +
  'a=a.data;' +
  'postMessage({i:a.i+1});' +
  'var e;' +
  'var x;' +
  'try {' +
  '  x = eval.call(this, a.c);' +
  '} catch (ex) {' +
  '  console.log(ex);' +
  '  e = ex.toString();' +
  '};' +
  'postMessage({' +
  '  r:x,' +
  '  o:output,' +
  '  e:e,' +
  '  i:a.i' +
  '})};' +
  '';
  var blob = new Blob([script], { type:'text/javascript' } ),
    myWorker = new Worker(URL.createObjectURL(blob));

  function onDone() {
    URL.revokeObjectURL(blob);
    fnOnStop.apply(this, arguments);
  }

  myWorker.onmessage = function (data) {
    data = data.data;
    if (data) {
      if (data.i === id) {
        if(typeof data.cmd != "undefined") {
          if(data.cmd == 'plot') {
            window.raphael.rect(data.x, data.y, 1, 1).attr({"fill": data.c, "stroke": data.c});
          } else if(data.cmd == 'rect') {
            window.raphael.rect(data.x, data.y, data.l, data.w).attr({"fill": data.c, "stroke": data.c});
          }
        } else if(typeof data.e != "undefined") {
          id = 0;
          onDone(false, data.e);
          myWorker.terminate();
        } else if(typeof data.r != "undefined" || typeof data.o != "undefined" && data.o != "") {
          id = 0;
          onDone(true, data.r,data.o);
          myWorker.terminate();
        } else {
          if(typeof data.o != "undefined" && data.o != "") {
            if(typeof output != "undefined") {
             output = output+data.o+"\n";
            }
          }
        }
      } else if (data.i === id + 1) {
        setTimeout(function() {
          if (id) {
            myWorker.terminate();
            onDone(false);
          }
        }, opt_timeoutInMS || 1000);
      }
    }
  };

  myWorker.postMessage({c: 'var id=' + id + ';\n' + code, i: id});
}

// ======== spinners, refresh and errors

/** spinner while flow runs */
function hideSpinner() {
  $('#refresher').show();
  $('#spinner').hide();
};

/** spinner while flow runs */
function showSpinner() {
  $('#refresher').hide();
  $('#spinner').show();
};

function setErrors(nfailed, nerrors, ntotal) {
  $('#errors').text(nfailed + '/' + nerrors + '/' + ntotal);
  $('#errors').removeClass();
  if (nfailed == 0 || nfailed == "0") {
    if (nerrors == 0 || nerrors == "0") {
      $('#errors').prop("title", "all tests ok!");
      $('#errors').addClass("label label-success");
    } else {
      $('#errors').prop("title", "no failures, but some exceptions");
      $('#errors').addClass("label label-warning");
    }
  } else {
    $('#errors').prop("title", "failed/errors/total");
    $('#errors').addClass("label label-danger");
  }
}

/** show something on internal errors */
function fiddle_showPageError(err) {
  $("#navJsError").show();
  $("#navJsError").attr("title", "ERROR from server: \n\n" + JSON.stringify(err));
}


// ================ running engine management

engineCancel   = function () {cancelFlow("cancel");};
enginePause    = function () {cancelFlow("pause");};
enginePlay     = function () {cancelFlow("play");};
engineContinue = function () {cancelFlow("continue");};
engineQueue    = function () {cancelFlow("queue");};

var cancelFlow = function(command) {
  if(currEngineId == null) {
    console.log ("Ignore cancelFlow - was done: " + curDomRequest);
    return;
  }

  console.log("cancelFlow currEngineId="+currEngineId);

  $.ajax(
    '/diesel/engine/'+currEngineId+'/' + command, {
      type: 'POST',
      success: function(data) {
        console.log("  OK " +command+" currEngineId="+currEngineId);
      },
      error  : function(x) {
        console.log("  ERR " +command+" currEngineId="+currEngineId);
        console.log("  ERR "+JSON.stringify(x));
      }
    });
};


// ================ play

/** run the fiddle */
var runFiddleStoryUpdated = function(input) {
  var id = input.id;
  var data = input.data;
  var compileOnly = input.compileOnly;
  var forceRT = input.forceRT;
  var next = input.next;

  isRefreshing2 += 1;
  showSpinner();
  console.log("run isRefreshing2="+isRefreshing2);

  var noSim = !(forceRT || false);

  // set this before going to backend which could take a long time
  //fiddle_storyChanged(!(specEditor.getValue() === $("#origStory_@id").text()));

  if(curDomRequest != null && !compileOnly) {
    console.log ("Ignore request - already in progress: " + curDomRequest);
    return;
  }

  engineCancel(); // cancel previous engine - small cost if already done

  if(!compileOnly) curDomRequest = "yes";

  lastStartTime = new Date().getTime();

  data.needsCAMap = needsContentAssist(false, compileOnly);
  data.needsBaseCA = isObjectEmpty(baseContentAssist);

    $.ajax(
      url = input.url, {
      type: 'POST',
      data: $.param(data),
      contentType: 'application/x-www-form-urlencoded',

      success: function(response) {
        console.log("New timestamp: " + response.info.timeStamp);
        clientStoryTimeStamp = response.info.timeStamp;

        processEngineResult(input, id, response, compileOnly, lastStartTime, next);

        $("#navJsError").hide();

        var duration = new Date().getTime() - lastStartTime;
        isSlow = duration > 500;

        if(typeof onSuccess === "function") {
          onSuccess(response);
        }

      },

      error  : function(response) {
        isRefreshing2 -= 1;

        hideSpinner();

        console.log( "ERR "+JSON.stringify(response));

        fiddle_showPageError("ERROR from server: \n\n" + JSON.stringify(response));

        if(!compileOnly) curDomRequest = null;

        if(JSON.stringify(response).indexOf("staleid") >= 0) {
          damnStale();
        }

        if(typeof onError === "function") {
          onError(response);
        }

      }
    });
};

var damnStale = function() {
  console.log("Stale page");
  errMsg("Stale DRAFT - please refresh to get the latest saved draft!!!");
  // location.reload();
}

function processEngineResult(input, id, response, compileOnly, lastTime, next) {
  console.log("RES: engineId:"+response.info.engineId);
  console.log("  RES data.info: " +JSON.stringify(response.info));

  currEngineId = response.info.engineId;
  currEngineData = response;

  lastProcessTime = new Date().getTime();

  if(response.info.engineDone) {

    console.log("  RESULT engine.done - processing");
    processFinalEngineResult(input, id, currEngineData, compileOnly, lastTime, next);
    currEngineId = null;

  } else {

    // engine not done yet - wait for websocket response
    console.log("  RESULT NOT engine.done - waiting");
    processPartialEngineResult(input, id, response, compileOnly, lastTime, next);
  }
};

/** partial response - engine not complete, but story compiled - update markers */
function processPartialEngineResult(input, id, response, compileOnly, lastTime, next) {
  if(storyEditor && typeof response.ast != "undefined") {
    storyHasErrors = updateMarkers(storyEditor, response.ast);
    if(storyHasErrors) {
      $("#storyChanged").addClass("label label-danger");
      $("#storyChanged").prop('title', "has errors");
    }
  }

  console.log("  updateMarkers story");

  if(response.info.enginePaused == 'true')
    $('#roundtrip').html('' +
      '<span class="glyphicon glyphicon-play" onclick="javascript:enginePlay()" style="cursor:pointer; color:red" title="Play one step"></span>' +
      '&nbsp;'+
      '<span class="glyphicon glyphicon-forward"  onclick="javascript:engineContinue()" style="cursor:pointer; color:red" title="Continue flow"></span>' +
      '' + response.info.progress+'');
  else if(response.info.enginePaused == 'false')
    $('#roundtrip').html('' +
      '<span class="glyphicon glyphicon-remove" onclick="javascript:engineCancel()" style="cursor:pointer; color:red" title="Cancel flow"></span>' +
      '&nbsp;'+
      '<span class="glyphicon glyphicon-pause"  onclick="javascript:enginePause()" style="cursor:pointer; color:red" title="Pause flow"></span>' +
      '' + response.info.progress+'');

  else
    $('#roundtrip').html('' +
      '' + response.info.progress+'');

  $('#roundtrip').prop('title', "failed/tested/todo");

  if(typeof response.storyChanged != "undefined") fiddle_storyChanged(response.storyChanged);

  fiddle_showFinalEngineResult(id, compileOnly);

  // we check all the time, to update progress, anyways...
  // if(! wsActive) {
  setTimeout(function () {
    checkpill2(id);
  }, CHECK_TIMER);
  // }
};

/** final response - engine complete - update everything */
function processFinalEngineResult(input, id, response, compileOnly, lastTime, next) {
  isRefreshing2 -= 1;
  hideSpinner();

  fiddle_showFinalEngineResult(id, compileOnly);

  if(!compileOnly) {
    fiddle_showCapture(id, response);

    if(Object.keys(response.baseCA).length > 0)  baseContentAssist = response.baseCA;
    if(Object.keys(response.storyCA).length > 0) instContentAssist = concatCA(baseContentAssist, response.storyCA);

    fiddle_setErrors(response);

    var du = new Date().getTime() - lastTime;
    $('#roundtrip').text(du + ' ms');
    $('#roundtrip').prop('title', "real time roundtrip response time");

    // automatic backoff
    if(du > 9000)
      currDebounce = 7000;
    else if(du > 7000)
      currDebounce = 5000;
    else if(du > 2000)
      currDebounce = 3000;
    else
      currDebounce = 2000;
  }

  fiddle_updateStoryMarkers(response);

  console.log("  updateMarkers story");

  if(!compileOnly) curDomRequest = null;
  if(typeof response.storyChanged != "undefined" && typeof fiddle_storyChanged === "function") fiddle_storyChanged(response.storyChanged);

  if(typeof next === "function") {
    next();
  }
};


/** combine two doms, from a base */
function concatCA (doma, domb) {
  // var m = doma.msg != null  doma.msg.concat(domb.msg),
  //   "attr": doma.attr.concat(domb.attr)
  return {
    "msg": doma.msg.concat(domb.msg),
    "attr": doma.attr.concat(domb.attr)
  }
}

function showFinalEngineResult(id) {
  var x = typeof compileOnly != "undefined" ? compileOnly : false;
  fiddle_showFinalEngineResult(id, x);
}

/** setup the logging */
function setupLogLevels(id) {
  // todo could optimize and not showFinal every time trace/debug but only if payload was on
  useLocalStorageCheckbox("verboseStory", "domFiddleVerboseStory", function (a,b,expanded) {
    if(expanded) $('#payloadOnly').prop('checked', false).change(); // change triggers the callback
    if(currEngineData != null) showFinalEngineResult(id); // repaint
    dieselHideVerbose(expanded);
  });

  // todo could optimize and not showFinal every time trace/debug but only if payload was on
  useLocalStorageCheckbox("traceStory", "domFiddleTraceStory", function (a,b,expanded) {
    if(expanded) $('#payloadOnly').prop('checked', false).change(); // change triggers the callback
    if(currEngineData != null) showFinalEngineResult(id); // repaint
    dieselHideTrace(expanded);
  });

  useLocalStorageCheckbox("debugStory", "domFiddleDebugStory", function (a,b,expanded) {
    if(expanded) $('#payloadOnly').prop('checked', false).change(); // change triggers the callback
    if(currEngineData != null) showFinalEngineResult(id); // repaint
    dieselHideDebug(expanded);
  });

  useLocalStorageCheckbox("generatedStory", "domFiddleGeneratedStory", function (a,b,expanded) {
    if(expanded) $('#payloadOnly').prop('checked', false).change(); // change triggers the callback
    if(currEngineData != null) showFinalEngineResult(id); // repaint
    dieselHideGenerated(expanded);
  });

  useLocalStorageCheckbox("payloadOnly", "domFiddlePayloadOnly", function (a,b,expanded) {
    if(expanded) {
      // turn off the nodes
      $('#traceStory').prop('checked', false).change();
      $('#debugStory').prop('checked', false).change();
      $('#generatedStory').prop('checked', false).change();
    } else {
      // turn on the nodes
      $('#traceStory').prop('checked', true).change();
      $('#debugStory').prop('checked', true).change();
      $('#generatedStory').prop('checked', true).change();
    }

    if(currEngineData != null) showFinalEngineResult(id); // repaint
  });

}