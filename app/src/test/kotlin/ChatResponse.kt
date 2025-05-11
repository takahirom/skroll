// MyApiTests.kt
// Typically in src/test/kotlin/com/example/MyApiTests.kt

import com.google.common.truth.Truth.assertThat
import io.github.takahirom.skroll.ApiResponse
import io.github.takahirom.skroll.FakeCurlExecutor
import io.github.takahirom.skroll.skrollTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName

data class Message(val role: String, val content: String)

fun getMatterFromResponse(response: ApiResponse): Message {
  val jsonString = response.body

  val jsonObject = Json {}.decodeFromString<JsonObject>(jsonString)
  val messageObject = jsonObject["choices"]?.jsonObject?.get("message")?.jsonObject
  return Message(
    role = messageObject?.get("role")?.jsonPrimitive?.content ?: "unknown",
    content = messageObject?.get("content")?.jsonPrimitive?.content ?: "no content"
  )
}

class MyApiTests {

  @Test
  @DisplayName("Joke API should respond correctly to a standard prompt from resource file")
  fun `joke api standard prompt from resource`() {
    skrollTest("Standard Joke Test") {
      setCurlExecutor(FakeCurlExecutor)
      defaultFixture(
        name = "Standard API Config",
        data = mapOf(
          "API_URL" to "https://api.example.com", // Replace with a mock server or real endpoint if testing live
          "API_KEY" to "test_api_key_123",
          "SYSTEM_PROMPT" to "You are a helpful joke-telling assistant."
        )
      )

      curlCases { // Uses the defaultFixture defined in this skrollTest block
        caseFromResource(
          name = "Fetch a joke using case1.txt",
          commandTemplateResourcePath = "case1.txt" // Assumes case1.txt is in resources
        ) { apiResponse ->
          assertThat(apiResponse.statusCode).isEqualTo(200)
          val matter = getMatterFromResponse(apiResponse)
          assertThat(matter.role).isEqualTo("assistant")
          assertThat(matter.content).contains("Here is a joke:")
        }

        // Add more cases that use the same defaultFixture here
        case(
          name = "Simple GET to status endpoint",
          curlCommandTemplate = "curl -X GET {API_URL}/status"
        ) { apiResponse ->
          assertThat(apiResponse.statusCode).isEqualTo(200) // Assuming /status returns 200
          assertThat(apiResponse.body).contains("OK") // Assuming body contains OK
        }
      }
    }
  }

  @Test
  @DisplayName("Joke API should respond differently to various system prompts")
  fun `joke api with varying system prompts`() {
    skrollTest("Iterative Prompt Test") {
      setCurlExecutor(FakeCurlExecutor)
      val promptFixtures = fixtures {
        add(
          name = "Helpful Joke Fixture",
          data = mapOf(
            "API_URL" to "https://api.example.com",
            "API_KEY" to "test_api_key_123",
            "SYSTEM_PROMPT" to "You are a helpful joke-telling assistant."
          )
        )
        add(
          name = "Sarcastic Joke Fixture",
          data = mapOf(
            "API_URL" to "https://api.example.com",
            "API_KEY" to "test_api_key_456",
            "SYSTEM_PROMPT" to "You are a sarcastic joke-telling assistant who reluctantly tells jokes."
          )
        )
      }

      // These cases will run for each fixture in promptFixtures
      curlCases(promptFixtures) { currentFixture ->
        caseFromResource(
          name = "Joke test for ${currentFixture.name}",
          commandTemplateResourcePath = "case1.txt"
        ) { apiResponse ->
          assertThat(apiResponse.statusCode).isEqualTo(200)
          val matter = getMatterFromResponse(apiResponse)
          assertThat(matter.role).isEqualTo("assistant")

          assertThat(matter.content).contains("Here is a joke:")
        }
      }
    }
  }
}
