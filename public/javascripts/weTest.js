/**
 * Created by razvanc on 02/05/2014.
 */

var testState="none";
var target="";
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

function sok(url, s) {
  return function(next) {
    testGet(url, function(result) {
      var res = result;
      if(typeof result == 'object') res = JSON.stringify(result);

      if (res.indexOf(s) >= 0) {
        report("OK "+url + "\t"+s, false);
      } else {
        report("NOT_CONTAINS "+url + "\t"+s, true);
      }
      next();
    }, function(err) {
      report("ERR_AJAX "+url + "\t"+JSON.stringify(err), true);
      next();
    })
  }
}

function nok(url, s) {
  return function(next) {
    testGet(url, function(result) {
      var res = result;
      if(typeof result == 'object') res = JSON.stringify(result);
      if (res.indexOf(s) >= 0) {
        report("CONTAINS "+url + "\t"+s, true);
      } else {
        report("OK "+url + "\t"+s, false);
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

function testGet(url, check, ferr) {
  try {
    $.ajax({
      type: "GET",
      url:"http://"+target+url,
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


//err('', 403)
function runSuites() {
  testState = "running";
  var curCount = 0;

  var tests = testApi();

  function testCase(i) {
    function continueTest() {
      if(i < tests.length-1 && testState == "running") setTimeout(testCase(i+1), 50);
      else if(testState == "running") testState = "done";
    }

    $("#cur").text(i);
    $("#count").text(i);

    var t1=new Date().getTime();


    try {
      tests[i](continueTest);
    } catch(err) {
      continueTest();
    }
  }

  testCase(0);
}

function testApi() {
  var tests = [
    // test API
    sok('/weapi/v1/entry/rk.Admin:TestPublic',         'Testing'),
    sok('/weapi/v1/content/rk.Admin:TestPublic',       'Testing'),
    sok('/weapi/v1/html/rk.Admin:TestPublic',          'Testing'),
    sok('/weapi/v1/entry/id/574f1c26b0c8520467c017c7', 'Testing'),
    nok('/weapi/v1/entry/ver/1/rk.Admin:TestPublic',   'Testing'),

    // test private
    auth('None'),
    err('/weapi/v1/entry/rk.Admin:TestPrivate',        401),
    err('/weapi/v1/content/rk.Admin:TestPrivate',      401),
    err('/weapi/v1/html/rk.Admin:TestPrivate',         401),
    err('/weapi/v1/entry/id/506c867a0cf26592618ee264', 401),
    err('/weapi/v1/entry/ver/1/rk.Admin:TestPrivate',  401),
    auth(''),

    err('/weapi/v1/entry/rk.A:dm:in:TestPublic',       404)
    ];
  return tests;
}



function setTarget() {
  target=$("#target").val();
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
    url:"http://"+target+"/razadmin/wlist/all?hostname="+target,
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

  getPageList();

  $("#count").text(pageList.length);

  function testPage(i) {
    function continueTest() {
      if(i >=0  && testState == "running") setTimeout(testPage(i-1), 50);
      else if(testState == "running") testState = "done";
    }

    $("#cur").text(pageList[i].cat+ ":" + pageList[i].name);
    $("#count").text(i);

    var t1=new Date().getTime();

    try {
      $.ajax({
        type: "GET",
        url:"http://"+target+"/wiki/fragById/"+pageList[i].cat+ "/" + pageList[i].id,
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

function runStressTest() {
  testState = "running";
  var curCount = 0;
  var rollingAverage = 0.0;
  var threads = 5;

  function incAverage(msec) {
    rollingAverage = (rollingAverage*(curCount-1) + msec)/curCount;
    $("#average").text(rollingAverage);
  }

  getPageList();

  $("#count").text(curCount);

  function testPage(i) {
    function continueTest() {
      if(i > 0 && testState == "running") setTimeout(testPage(i-1), 50);
      else if(testState == "running") setTimeout(testPage(pageList.length-1), 50);
    }

    $("#cur").text(pageList[i].cat+ ":" + pageList[i].name);
    curCount = curCount+1;
    $("#count").text(curCount);

    var t1=new Date().getTime();

    try {
      $.ajax({
        type: "GET",
        url:"http://"+target+"/wiki/fragById/"+pageList[i].cat+ "/" + pageList[i].id,
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
            incAverage(t2-t1);
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

