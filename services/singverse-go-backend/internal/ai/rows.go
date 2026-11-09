package ai

// Persistence rows use database column names; API views never contain secrets.
type row struct {
	ID             string  `db:"id"`
	Kind           string  `db:"kind"`
	Name           string  `db:"name"`
	Provider       string  `db:"provider"`
	Protocol       string  `db:"protocol"`
	Endpoint       string  `db:"endpoint"`
	Model          string  `db:"model"`
	OutputMode     string  `db:"output_mode"`
	Secret         *string `db:"secret_encrypted"`
	Purpose        string  `db:"credential_purpose"`
	Enabled        bool    `db:"enabled"`
	TimeoutSeconds int     `db:"timeout_seconds"`
	MaxResults     int     `db:"max_results"`
	Revision       int64   `db:"revision"`
}

func (r row) view() ServiceView {
	return ServiceView{
		ID: r.ID, Kind: r.Kind, Name: r.Name, Provider: r.Provider,
		Protocol: r.Protocol, Endpoint: r.Endpoint, Model: r.Model,
		OutputMode: r.OutputMode, HasKey: r.Secret != nil, Enabled: r.Enabled,
		TimeoutSeconds: r.TimeoutSeconds, MaxResults: r.MaxResults, Revision: r.Revision,
	}
}

type featureRow struct {
	ID              string  `db:"id"`
	ModelServiceID  *string `db:"model_service_id"`
	SearchServiceID *string `db:"search_service_id"`
	Enabled         bool    `db:"enabled"`
	IntervalSeconds int     `db:"interval_seconds"`
	Revision        int64   `db:"revision"`
	DisplayMode     string  `db:"display_mode"`
	PetID           string  `db:"pet_id"`
}

func (r featureRow) view() Feature {
	return Feature(r)
}
