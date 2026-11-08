package co.edu.unipamplona.canchatrack.identity.application;

import java.util.List;

/** Alta de una cuenta de personal del club. Los roles llegan como códigos de texto y se validan en el caso de uso. */
public record CreateUserCommand(String email, String fullName, String password, List<String> roles) {

    @Override
    public String toString() {
        return "CreateUserCommand[email=" + email + ", fullName=" + fullName + ", password=***, roles=" + roles + "]";
    }
}
