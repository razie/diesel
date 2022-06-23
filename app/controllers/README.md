Most controllers are here.


## Classes etc

Controllers inherit from RazController.

There is a RazRequest which enhances a play Request.


## Auth

Except for view/browse actions, which are public, most are scoped to 
an "active user" or FAU.

An admin (FA or FAD) is either an user with adminDb priviliges or,
in localhost mode (i.e. not the main dieselapps cloud) a "mod".



