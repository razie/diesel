package model

case class Sport(name: String, img: String, icon: String) {}

case class Country(name: String, id: String) {}
case class Province(name: String, id: String, country: Country) {}
case class Region(name: String, id: String, province: Province) {}

case class Trophy(name: String, img: String) {}
case class Result(place: String, event: Event) {}

case class Event(date: String, desc: String, region: Region, race: Boolean) {}
case class Calendar(dateRange: String, region: Region) {}

abstract class Media(date: String, desc: String, event: Event) {}
case class Photo(date: String, desc: String, event: Event) extends Media(date, desc, event)
case class Video(date: String, desc: String, event: Event) extends Media(date, desc, event)
