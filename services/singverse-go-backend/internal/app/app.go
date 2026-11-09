// Package app is the composition root. Business packages do not own globals.
package app

import (
	"errors"
	"log/slog"
	"sync"
	"sync/atomic"

	"github.com/gin-gonic/gin"
	"github.com/jmoiron/sqlx"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/background"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/config"
)

type Application struct {
	Router    *gin.Engine
	db        *sqlx.DB
	tasks     *background.Service
	closers   []func() error
	closeOnce sync.Once
	closeErr  error
	requests  atomic.Int64
	nanos     atomic.Int64
	active    atomic.Int64
}

func New(cfg config.Config, db *sqlx.DB, log *slog.Logger) (_ *Application, err error) {
	if log == nil {
		log = slog.Default()
	}
	application := &Application{db: db}
	defer func() {
		if err != nil {
			application.Close()
		}
	}()

	services, err := application.initializeServices(cfg, db, log)
	if err != nil {
		return nil, err
	}
	application.Router, err = application.newRouter(services, log)
	if err != nil {
		return nil, err
	}
	if err = services.downloads.Start(); err != nil {
		return nil, err
	}
	return application, nil
}

func (a *Application) Close() error {
	if a == nil {
		return nil
	}
	a.closeOnce.Do(func() {
		for i := len(a.closers) - 1; i >= 0; i-- {
			a.closeErr = errors.Join(a.closeErr, a.closers[i]())
		}
	})
	return a.closeErr
}
