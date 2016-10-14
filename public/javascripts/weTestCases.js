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

var suites = [[], testApi(), testScripting(), testNotes(), testClubs(), testSkiing()];


