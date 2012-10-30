package admin

import com.mongodb.DBObject
import scala.collection.generic.CanBuildFrom
import scala.collection.GenTraversableOnce

/** filter your collections before going through here - these are not lazy */
object M {
  case class ML[A](l: List[A]) {
    def >>   [B, That](f: A => B)(implicit bf: CanBuildFrom[List[A], B, That]): List[B] = l map f
    def >>>  [B, That](f: A => GenTraversableOnce[B])(implicit bf: CanBuildFrom[List[A], B, That]): List[B] = l flatMap f
//    def >>=  [B, That](f: A => GenTraversableOnce[B])(implicit bf: CanBuildFrom[List[A], B, That]): List[B] = l flatMap f
    
//    def >>> [B](f: A => List[B]): List[B] = l flatMap f
//    def >>  [B](f: A => B): List[B]       = l map f
    def ??     (f: A => Boolean): Boolean = l exists f
  }

  implicit def toML[A](l: List[A]) = ML(l)
  implicit def toML2[A](l: Option[A]) = ML(l.toList)
  
  implicit def toML1(l: com.mongodb.casbah.MongoCursor) = ML[DBObject](l.toList)

  //  List(1, 2, 3) >> (1 + _)
  //
  //  List(1, 2, 3) >>> (List(1, _))
}

