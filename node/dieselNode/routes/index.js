var express = require('express');
var router = express.Router();

/* GET home page. */
router.get('/', function(req, res, next) {
  res.render('index', { title: 'Express' });
});


var db = {
  sub123 : {
    'id' : 'sub123',
    'name' : '123',
    'address' : 'home'
  }
}

//////////////// sub service

router.post('/sub/create/:subId', function(req, res, next) {
  var id = req.params.subId;
  var sub = {
    'id' : id,
    'name' : req.body.sub.name,
    'address' : req.body.sub.address
  };

  // persist
  db[id] = sub;

  res.setHeader('Content-Type', 'application/json');
  res.send(JSON.stringify(sub));
});

router.get('/sub/load/:subId', function(req, res, next) {
  var id = req.params.subId;
  res.setHeader('Content-Type', 'application/json');
  res.send(JSON.stringify(db[id]));
});

// sample code
router.get('/login', function (req, res) {
  console.log(req.body);
  res.setHeader('Content-Type', 'text/html');
  res.setHeader('Set-Cookie', 'COOKIE=2668e30300035b10; PATH=/; MAXAGE=9999; VERSION=1');
  res.write('ok');
  res.end();
});








module.exports = router;
