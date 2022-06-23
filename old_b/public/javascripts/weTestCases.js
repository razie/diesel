/**
 * Created by razvanc on 02/05/2014.
 */

function testApi() {
  var tests = [
    // test API
    sok('/weapi/v1/entry/rk.Admin:TestPublic',         'Testing'),
    sok('/weapi/v1/content/rk.Admin:TestPublic',       'Testing'),
    sok('/weapi/v1/html/rk.Admin:TestPublic',          'Testing'),
    sok('/weapi/v1/entry/id/574f1c26b0c8520467c017c7', 'Testing'),
    sok('/weapi/v1/entry/ver/1/rk.Admin:TestPublic',   'Testing'),

    // test private
    auth('None'), // switch user
    err('/weapi/v1/entry/rk.Admin:TestPrivate',        401),
    err('/weapi/v1/content/rk.Admin:TestPrivate',      401),
    err('/weapi/v1/html/rk.Admin:TestPrivate',         401),
    err('/weapi/v1/entry/id/574f1c47b0c8520467c017da', 401),
    err('/weapi/v1/entry/ver/1/rk.Admin:TestPrivate',  401),
    auth(''), // switch user back

    // not found
    err('/weapi/v1/entry/rk.A:dm:in:TestPublic',       404)
  ];
  return tests;
}

function testNotes() {
  var tests = [
    sok('/notes/view/id/536780fae4b0d9a94988b9ce', 'Testing'),
    sok('/notes/id/536780fae4b0d9a94988b9ce', 'Testing'),

    sok('/notes/view/id/57f93b30d4c6f0357714a804', 'Testing'), // public note
    sok('/notes/id/57f93b30d4c6f0357714a804', 'Testing'), // public note

    // test private
    auth('None'),
    // won't give a 401 but redirect as Harry
    nok('/notes/view/id/536780fae4b0d9a94988b9ce', 'Testing'),
    nok('/notes/id/536780fae4b0d9a94988b9ce', 'Testing'),

    //todo should make this work
    //sok('/notes/view/id/57f93b30d4c6f0357714a804', 'Testing'), // public note
    //sok('/notes/id/57f93b30d4c6f0357714a804', 'Testing'), // public note
    auth('')
  ];
  return tests;
}

function testNotes() {
  var tests = [
    sok('/notes/view/id/536780fae4b0d9a94988b9ce', 'Testing'),
    sok('/notes/id/536780fae4b0d9a94988b9ce', 'Testing'),

    sok('/notes/view/id/57f94a53e4b0506f041c08b5', 'Testing'), // public note
    sok('/notes/id/57f94a53e4b0506f041c08b5', 'Testing'), // public note

    // test private
    auth('None'),
    // won't give a 401 but redirect as Harry
    nok('/notes/view/id/536780fae4b0d9a94988b9ce', 'Testing'),
    nok('/notes/id/536780fae4b0d9a94988b9ce', 'Testing'),

    //todo should make this work
    //sok('/notes/view/id/57f94a53e4b0506f041c08b5', 'Testing'), // public note
    //sok('/notes/id/57f94a53e4b0506f041c08b5', 'Testing'), // public note
    auth('')
  ];
  return tests;
}

function testClubs() {
  var tests = [
    // test API
//    sok('/improve/skiing/view', '<title>View progress</title>'),
    //sok('http://www.effectiveskiing.com/improve/skiing/view', '<title>'),

    // test private
    //auth('None'),
    //err('/weapi/v1/entry/ver/1/rk.Admin:TestPrivate',  401),
    auth('')
  ];
  return tests;
}

function testScripting() {
  var tests = [
    // test API
    sok('/wiki/rk.Club:Glacier_Ski_Club', 'Enjoy your membership')
  ];
  return tests;
}

function testSkiing() {
  var tests = [
    // test API
//    sok('/improve/skiing/view', '<title>View progress</title>'),
    //sok('http://www.effectiveskiing.com/improve/skiing/view', '<title>'),

    // test private
    //auth('None'),
    //err('/weapi/v1/entry/ver/1/rk.Admin:TestPrivate',  401),
    auth('')
  ];
  return tests;
}


function testcrUser(n, role) {
  return function(next) {
    crUser(n,role);
    next();
  }
}

/** create a user */
function crUser(n, role) {
  var form = {
    "firstName" : n,
    "lastName" : '',
    "yob" : "1991",
    "address" : "City of future",
    "userType" : role,
    "accept" : "true",
    "about" : "ha",
    "g-recaptcha-response" : TESTCODE
  };
  var email = "H-" + n + "@k.com"; // k.com is recognized elsewhere

  // maybe user already created, be nice - this is for testing
  var userId = wget("/testingRaz/userIdByFirstName/"+n+"/"+TESTCODE);

  if (userId == "") {
    var res;
    res = pwget("/doe/profile/create?testcode=" + TESTCODE, form); // sok "we sent an emai"
    //    Thread.sleep(1000)
    userId = wget("/testingRaz/userIdByFirstName/"+n+"/"+TESTCODE);

    //userId should have length (24)
    if(userId.length != 24)
      report("ERR crUser " + "\t" +userId, true);
    else {
      report("OK crUser " + "\t" +userId , false);

    res = wget("/testingRaz/auth/x/"+TESTCODE);
    res = wget("/testingRaz/verifyUserById/"+userId+"/"+TESTCODE);
      // I don't what this :
    //res = wget("/testingRaz/setuserUsernameById/"+userId+"/"+TESTCODE);
    console.log("User created: " + "H-" + n + " "+ userId);
    }
  }
}

function testPros() {
  var pro = 'a1'+testCYCLE,
    guest = 'a2'+testCYCLE;
  var tests = [
    testcrUser(pro, 'Pro'),
    auth("H-" + pro + "@k.com", "H-"+TESTCODE),
    sok('/doe/consent2/Admin:Consent ver 2', 'Thank'),
    sok ("/", pro),
    auth(''),

    testcrUser(guest, 'Guest'),
    auth("H-" + guest + "@k.com", "H-"+TESTCODE),
    sok('/doe/consent2/Admin:Consent ver 2', 'Thank'),
    sok ("/", guest),
    auth(''),

    auth("H-" + pro + "@k.com", "H-"+TESTCODE),
    //psok ("/4us/activate/Pro", {
    psok ("/doe/form/submit/Form:a_form", {
      discipline : 'alpine',
      system : 'effective',
      cbCalendar : 'y',
      cbForum : 'y',
      cbBuyAndSell : 'y',
      cbApprove : 'n',
      desc : 'description',
      certs : 'certifications',
      photo : '',
      weNextUrl : '/4us/activate/Pro',
      weContent : '',
      video : ''
    }, "certifications"),

    auth(''),



    auth('')
  ];
  return tests;
}

// lazy after testCYCLE init
var suites = function () {
  return {
    testPros: testPros(),
    testAPI: testApi(),
    testScripting: testScripting(),
    testnotes: testNotes(),
    testClubs: testClubs(),
    testSkiing: testSkiing()
  };
};


