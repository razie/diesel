package jplay;

public class Titi {
    interface Person {
        String getName();
        String getEmail();
    }

    public static class Student implements Person {
        String name;
        String email;

        public Student (String n, String e) {
            name = n; email = e;
        }

        @Override
        public String getName() { return name; };
        @Override
        public String getEmail() { return email; };
        @Override
        public String toString() { return name+email;};
    }

    public static void main(String []args){
        Student s = new Student ("n", "e");
        System.out.println(s.toString());
    }
}

