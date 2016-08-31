/**
 * Created by razvanc on 02/05/2014.
 */

var testState="none";

function target() {
  return $("#target").val();
}

var pageList = []; // list of pages available to test

function x() {
  $.getJSON('/notes/contacts', { q: term })
  .done(function (resp) {
    contacts = resp;
    callback(contacts.filter(function(value){
      return value.indexOf(term) == 0;
    }))
  })
  .fail(function (){ callback([]); });
}

/** add an error to the report */
function report(s,err) {
  if(err) {
    $("#err").append(s+"<br>");
    $("#topics").append('<span style="color:red">err - ' + s +"</span><br>");
  } else {
    $("#topics").append('<span style="color:blue">ok - ' + s +"</span><br>");
  }
}

function mkTest(f) {
}

/** test that an URL returns an error code */
function err(url, code) {
  return function(next) {
    testGet(url, function(result) {
      report("NOT_CODE "+url + "\t"+code+" GOT: 200 "+result, true);
      next();
    }, function(err) {
      if(err.status == code)
        report(code+url + "\t"+JSON.stringify(err), false);
      else
        report("ERR_AJAX "+url + "\t"+JSON.stringify(err), true);
    next();
    })
  }
}

/** get URL 200 and make sure it includes string s */
function sok(url, s) {
  return snok (url, s, true);
}

/** get URL 200 and make sure it NOT includes string s */
function nok(url, s) {
  return snok (url, s, false);
}

/** get URL 200 and make sure it includes string s */
function snok(url, s, should) {
  return function(next) {
    testGet(url, function(result) {
      var res = result;
      if(typeof result == 'object') res = JSON.stringify(result);

      if (res.indexOf(s) >= 0 && should) {
        report("OK " + url + "\t" + s, false);
      } else if (res.indexOf(s) >= 0 && !should) {
        report("CONTAINS "+url + "\t"+s, false);
      } else {
        var not = (res.indexOf(s) >= 0) ? "" : "NOT_";
        report(not+"CONTAINS "+url + "\t"+s, true);
      }
      next();
    }, function(err) {
      report("ERR_AJAX "+url + "\t"+JSON.stringify(err), true);
      next();
    })
  }
}

var testHeaders={};

function auth(u,p) {
  return function(next) {
    if(u == 'None') {
      testHeaders = {
        "Authorization" : "None "
      };
    } else if(u.length > 0) {
      testHeaders = {
        "Authorization" : "Basic " + btoa(u+':'+p)
      };
    } else
      testHeaders = {};
    next();
  }
}

/** test url with given predicate */
function testGet(url, check, ferr) {
  var u = "http://"+target()+url;
  if(url.indexOf('http') == 0) u = url;

  try {
    $.ajax({
      type: "GET",
      url: u,
      async: true,
      headers: testHeaders,
        success: function (result){
          try {
            check(result)
          } catch (err) {
            ferr(err);
          }
          //continueTest();
        },
    error: function (result){
      //continueTest();
      ferr(result);
    }
  });
} catch(err) {
    ferr(err);
  //continueTest();
}
}


/**
 * Needs a var suites =[[],[]] is the array of arrays of callbacks.
 *
 * see admin_test.scala.html
 */
function runSuites() {
  testState = "running";
  var curCount = 0;

  function testCase(s,i) {
    function continueTest() {
      if(i < suites[s].length-1 && testState == "running")
        setTimeout(testCase(s,i+1), 50);
      else if(s < suites.length-1 && testState == "running")
        setTimeout(testCase(s+1,0), 50);
      else if(testState == "running")
        testState = "done";
    }

    curCount = curCount+1;

    $("#cur").text('suite '+s + ' test '+i);
    $("#count").text(curCount);

    var t1=new Date().getTime();

    try {
      suites[s][i](continueTest);
    } catch(err) {
      continueTest();
    }
  }

  testCase(0, 0);
}

/**
 * see streamSimulator.scala.html
 * streamNext (streamName, curIndex, continuation())
 * delay in msec between ticks
 */
function runStream(name, streamNext, delay, take) {
  testState = "running";
  var curCount = 0;

  function testCase(s,i) {

    function continueTest() {
      if(i < take-1 && testState == "running")
        setTimeout(function(){testCase(s,i+1)}, delay);
      else if(testState == "running")
        testState = "done";
    }

    curCount = curCount+1;

    $("#cur").text('suite '+s + ' test '+i);
    $("#count").text(curCount);

    var t1=new Date().getTime();

    try {
      streamNext(s, i, continueTest);

      var t2=new Date().getTime();
      incAverage(curCount, t2-t1);
    } catch(err) {
      continueTest();
    }
  }

  testCase(name, 0);
}

function pauseTest() {
  testState = "paused";
}
function stopTest() {
  testState = "stopped";
}

function getPageList() {
  $.ajax({
    type: "GET",
    url:"http://"+target()+"/razadmin/wlist/all?hostname="+target(),
    dataType: 'json',
    async: false,
  //headers: {
  //  "Authorization": "Basic " + btoa('H-Dec(stok.au.get.email)', + ":" + 'H-Dec(stok.au.get.pwd)')
  //},

      success: function (result){
        if (result.isOk == false) alert(result.message);
        pageList = result;
      }
});
}

function runContentTest() {
  testState = "running";
  var curCount = 0;

  getPageList();

  $("#count").text(pageList.length);

  function testPage(i) {
    function continueTest() {
      if(i >=0  && testState == "running")
        setTimeout(function(){testPage(i-1)}, 50);
      else if(testState == "running")
        testState = "done";
    }

    $("#cur").text(pageList[i].cat+ ":" + pageList[i].name);
    $("#count").text(i);

    var t1=new Date().getTime();

    try {
      $.ajax({
        type: "GET",
        url:"http://"+target()+"/wiki/fragById/"+pageList[i].cat+ "/" + pageList[i].id,
        async: true,
      //headers: {
      //  "Authorization": "Basic " + btoa('H-@Dec(stok.au.get.email)', + ":" + 'H-@Dec(stok.au.get.pwd)')
      //},
        success: function (result){
          if (result.indexOf("CANNOT PARSE") >= 0) {
            $("#err").append("CANNOT PARSE"+"<br>");
            $("#topics").append('<span style="color:red">err - ' + pageList[i].cat+ ":" + pageList[i].name+"</span><br>");
          } else {
            var t2=new Date().getTime();
            $("#topics").append("ok  - " + pageList[i].cat+ ":" + pageList[i].name+" "+result.split(" ").length+
              " words took "+(t2-t1) + " millis <br>");
//          $("#frag").text(result);
          }
          var t2=new Date().getTime();
          curCount = curCount+1;
          incAverage(curCount, t2-t1);
          continueTest();
        },
      error: function (result){
        $("#err").append(pageList[i].cat+ ":" + pageList[i].name+"<br>"+result.statusText+" - "+result.responseText+"<br>");
        $("#topics").append('<span style="color:red">err - ' + pageList[i].cat+ ":" + pageList[i].name+"</span><br>");
        continueTest();
      }
    });
  } catch(err) {
    $("#err").append(err+"<br>");
    $("#topics").append("err - " + pageList[i].cat+ ":" + pageList[i].name+"<br>");
    continueTest();
  }
} //testpage
// start
testPage(pageList.length-1);
}

var rollingAverage = 0.0;

function incAverage(curCount, msec) {
  rollingAverage = (rollingAverage*(curCount-1) + msec)/curCount;
  $("#average").text(Math.round(rollingAverage) + "  msec");
}

function runStressTest() {
  testState = "running";
  var curCount = 0;
  var threads = 5;

  getPageList();

  $("#count").text(curCount);

  function testPage(i) {
    function continueTest() {
      if(i > 0 && testState == "running")
        setTimeout(function(){testPage(i-1)}, 50);
      else if(testState == "running")
        setTimeout(function(){testPage(pageList.length-1)}, 50);
    }

    $("#cur").text(pageList[i].cat+ ":" + pageList[i].name);
    curCount = curCount+1;
    $("#count").text(curCount);

    var t1=new Date().getTime();

    try {
      $.ajax({
        type: "GET",
        url:"http://"+target()+"/wiki/fragById/"+pageList[i].cat+ "/" + pageList[i].id,
        async: true,
      //headers: {
      //  "Authorization": "Basic " + btoa('H-@Dec(stok.au.get.email)', + ":" + 'H-@Dec(stok.au.get.pwd)')
      //},
        success: function (result){
          if (result.indexOf("CANNOT PARSE") >= 0) {
//            $("#err").append("CANNOT PARSE"+"<br>");
            $("#topics").append('<span style="color:red">err - ' + pageList[i].cat+ ":" + pageList[i].name+"</span><br>");
          } else {
            var t2=new Date().getTime();
            incAverage(curCount, t2-t1);
//            $("#topics").append("ok  - " + pageList[i].cat+ ":" + pageList[i].name+" "+result.split(" ").length+
//              " words took "+(t2-t1) + " millis <br>");
//          $("#frag").text(result);
          }
          continueTest();
        },
      error: function (result){
//          $("#err").append(pageList[i].cat+ ":" + pageList[i].name+"<br>"+result.statusText+" - "+result.responseText+"<br>");
        $("#topics").append('<span style="color:red">err - ' + pageList[i].cat+ ":" + pageList[i].name+"</span><br>");
        continueTest();
      }
    });
  } catch(err) {
    $("#err").append(err+"<br>");
    $("#topics").append("err - " + pageList[i].cat+ ":" + pageList[i].name+"<br>");
    continueTest();
  }
} //testpage

// start parallel threads
for(t = 0; t < threads; t++)
  testPage(pageList.length-1);
}

