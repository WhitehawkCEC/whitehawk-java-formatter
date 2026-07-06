package example;

import java.util.Optional;

import org.jspecify.annotations.NullMarked;
import org.reactivestreams.Publisher;

import com.whitehawk.example.backend.core.jwt.EncodedJwt;
import com.whitehawk.example.backend.core.jwt.JwtParser;
import com.whitehawk.example.backend.core.jwt.ValidJwt;

import io.micronaut.security.authentication.Authentication;
import io.micronaut.security.token.jwt.validator.ReactiveJsonWebTokenValidator;
import jakarta.inject.Singleton;
import reactor.core.publisher.Mono;

@Singleton
@NullMarked
public record JwtParserImpl(ReactiveJsonWebTokenValidator<?, ?> validator) implements JwtParser {
  @Override
  public Optional<ValidJwt> parse(EncodedJwt encoded) {
    Publisher<Authentication> publisher = validator.validateToken(encoded.value(), null);
    return Mono.from(publisher).blockOptional().map(Authentication::getAttributes).map(ValidJwt::new);
  }
}
