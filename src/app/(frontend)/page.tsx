import ClubApp from "./ClubApp";

// Página estática: todos los datos llegan en el navegador desde el backend de Spring Boot (ver src/lib/api.ts).
export default function HomePage() {
  return <ClubApp />;
}
