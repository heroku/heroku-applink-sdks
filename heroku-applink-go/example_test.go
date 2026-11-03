package applink_test

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"net/http"
	"os"

	applink "github.com/heroku/heroku-applink-go"
)

func ExampleParseInvocationContext() {
	http.HandleFunc("POST /accounts", func(w http.ResponseWriter, r *http.Request) {
		invocation, err := applink.ParseInvocationContext(r.Header.Get)
		if err != nil {
			http.Error(w, "invalid x-client-context header", http.StatusBadRequest)
			return
		}

		log.Printf("Invoked by %s in org %s", invocation.User.Username, invocation.Org.ID)
		_ = json.NewEncoder(w).Encode(map[string]string{"requestId": invocation.RequestID})
	})
}

func ExampleGetAuthorization() {
	authorization, err := applink.GetAuthorization(context.Background(), "<my-authorization>")
	var httpError *applink.HTTPError
	if errors.As(err, &httpError) && httpError.Status == http.StatusNotFound {
		log.Fatal("The authorization doesn't exist.")
	}
	if err != nil {
		log.Fatal(err)
	}

	request, err := http.NewRequest(http.MethodGet, fmt.Sprintf("%s/services/data/v%s/query?q=SELECT+Id,Name+FROM+Account",
		authorization.Org.DomainURL, authorization.Org.APIVersion), nil)
	if err != nil {
		log.Fatal(err)
	}
	request.Header.Set("Authorization", "Bearer "+authorization.Credentials.AccessToken)
	response, err := http.DefaultClient.Do(request)
	if err != nil {
		log.Fatal(err)
	}
	defer response.Body.Close()
}

// Reads HEROKU_APPLINK_PURPLE_API_URL and HEROKU_APPLINK_PURPLE_TOKEN.
func ExampleWithAttachment_name() {
	authorization, err := applink.GetAuthorization(context.Background(), "<my-authorization>", applink.WithAttachment("HEROKU_APPLINK_PURPLE"))
	if err != nil {
		log.Fatal(err)
	}
	fmt.Println(authorization.Org.ID)
}

// Reads the same config vars as the name.
func ExampleWithAttachment_color() {
	authorization, err := applink.GetAuthorization(context.Background(), "<my-authorization>", applink.WithAttachment("purple"))
	if err != nil {
		log.Fatal(err)
	}
	fmt.Println(authorization.Org.ID)
}

// Uses the attachment whose _API_URL config var holds that URL.
func ExampleWithAttachment_apiURL() {
	apiURL := os.Getenv("HEROKU_APPLINK_PURPLE_API_URL")
	authorization, err := applink.GetAuthorization(context.Background(), "<my-authorization>", applink.WithAttachment(apiURL))
	if err != nil {
		log.Fatal(err)
	}
	fmt.Println(authorization.Org.ID)
}
