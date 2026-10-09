package alistlib

import (
	"errors"

	"github.com/OpenListTeam/OpenList/v4/cmd"
	"github.com/OpenListTeam/OpenList/v4/cmd/flags"
	"github.com/OpenListTeam/OpenList/v4/internal/model"
	"github.com/OpenListTeam/OpenList/v4/internal/op"
	"github.com/OpenListTeam/OpenList/v4/pkg/utils"
	"github.com/OpenListTeam/OpenList/v4/pkg/utils/random"
	"gorm.io/gorm"
)

func SetConfigData(path string) {
	flags.DataDir = path
}

func SetConfigLogStd(b bool) {
	flags.LogStd = b
}

func SetConfigDebug(b bool) {
	flags.Debug = b
}

func SetConfigNoPrefix(b bool) {
	flags.NoPrefix = b
}

func SetAdminPassword(pwd string) {
	admin, err := op.GetAdmin()
	if err != nil {
		// 内核 beta 起首次启动不再自动创建 admin（交由 Web 安装向导），
		// App 自带改密入口，记录缺失时按内核 initAdmin 的构造补齐
		if errors.Is(err, gorm.ErrRecordNotFound) {
			createAdmin(pwd)
			return
		}
		utils.Log.Errorf("failed get admin user: %+v", err)
		return
	}
	admin.SetPassword(pwd)
	if err := op.UpdateUser(admin); err != nil {
		utils.Log.Errorf("failed update admin user: %+v", err)
		return
	}
	utils.Log.Infof("admin user has been updated:")
	utils.Log.Infof("username: %s", admin.Username)
	utils.Log.Infof("password: %s", pwd)
	cmd.DelAdminCacheOnline()
}

// 用户构造与内核 internal/bootstrap/data/user.go initAdmin 保持一致
func createAdmin(pwd string) {
	salt := random.String(16)
	admin := &model.User{
		Username: "admin",
		Salt:     salt,
		PwdHash:  model.TwoHashPwd(pwd, salt),
		Role:     model.ADMIN,
		BasePath: "/",
		Authn:    "[]",
		// 0(can see hidden) - 8(webdav read) & 12(can read archives) - 14(can share)
		Permission: 0x71FF,
	}
	if err := op.CreateUser(admin); err != nil {
		utils.Log.Errorf("failed create admin user: %+v", err)
		return
	}
	utils.Log.Infof("admin user has been created:")
	utils.Log.Infof("username: %s", admin.Username)
	utils.Log.Infof("password: %s", pwd)
	cmd.DelAdminCacheOnline()
}
