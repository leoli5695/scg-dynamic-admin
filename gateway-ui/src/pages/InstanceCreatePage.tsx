import React, { useState, useEffect } from "react";
import {
  Card,
  Form,
  Input,
  Select,
  InputNumber,
  Button,
  Space,
  message,
  Spin,
  Tag,
} from "antd";
import {
  ClusterOutlined,
  ArrowLeftOutlined,
  CloudServerOutlined,
} from "@ant-design/icons";
import { useNavigate } from "react-router-dom";
import { useTranslation } from "react-i18next";
import axios from "axios";

const { Option } = Select;
const { TextArea } = Input;

interface KubernetesCluster {
  id: number;
  clusterName: string;
  connectionStatus: string;
  clusterVersion: string;
}

interface InstanceSpec {
  type: string;
  cpuCores: number;
  memoryMB: number;
  description: string;
}

const InstanceCreatePage: React.FC = () => {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const [form] = Form.useForm();
  const [loading, setLoading] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [clusters, setClusters] = useState<KubernetesCluster[]>([]);
  const [specs, setSpecs] = useState<InstanceSpec[]>([]);

  const selectedSpecType = Form.useWatch("specType", form);

  useEffect(() => {
    fetchData();
  }, []);

  const fetchData = async () => {
    setLoading(true);
    try {
      const [clustersRes, specsRes] = await Promise.all([
        axios.get("/api/kubernetes/clusters"),
        axios.get("/api/instances/specs"),
      ]);
      setClusters(clustersRes.data.data || []);
      setSpecs(specsRes.data.data || []);
    } catch (error) {
      console.error("Failed to fetch data:", error);
      message.error(t("common.fetch_error"));
    } finally {
      setLoading(false);
    }
  };

  const handleSubmit = async (values: any) => {
    setSubmitting(true);
    try {
      const response = await axios.post("/api/instances", values);
      if (response.data.code === 200) {
        message.success(t("instance.create_success"));
        navigate("/instances");
      } else {
        message.error(response.data.message || t("instance.create_failed"));
      }
    } catch (error: any) {
      message.error(error.response?.data?.message || t("instance.create_failed"));
    } finally {
      setSubmitting(false);
    }
  };

  const handleCancel = () => {
    navigate("/instances");
  };

  return (
    <div style={{ padding: "24px", maxWidth: "800px", margin: "0 auto" }}>
      <Card
        title={
          <Space>
            <Button
              type="text"
              icon={<ArrowLeftOutlined />}
              onClick={handleCancel}
            />
            <CloudServerOutlined />
            {t("instance.create_instance")}
          </Space>
        }
        styles={{
          header: { borderBottom: "1px solid rgba(255,255,255,0.1)" },
          body: { padding: "24px" },
        }}
        style={{
          background: "rgba(30, 41, 59, 0.95)",
          borderRadius: "12px",
        }}
      >
        <Spin spinning={loading}>
          <Form
            form={form}
            layout="vertical"
            onFinish={handleSubmit}
            initialValues={{
              specType: "medium",
              replicas: 1,
              createNamespace: true,
              image: "my-gateway:latest",
              imagePullPolicy: "IfNotPresent",
            }}
          >
            <Form.Item
              name="instanceName"
              label={t("instance.instance_name")}
              rules={[{ required: true, message: t("common.required") }]}
            >
              <Input placeholder={t("instance.instance_name_placeholder")} />
            </Form.Item>

            <Form.Item
              name="clusterId"
              label={t("instance.cluster")}
              rules={[{ required: true, message: t("common.required") }]}
            >
              <Select placeholder={t("instance.select_cluster")}>
                {clusters
                  .filter((c) => c.connectionStatus === "CONNECTED")
                  .map((cluster) => (
                    <Option key={cluster.id} value={cluster.id}>
                      <Space>
                        <ClusterOutlined />
                        {cluster.clusterName}
                        <Tag color="blue">{cluster.clusterVersion}</Tag>
                      </Space>
                    </Option>
                  ))}
              </Select>
            </Form.Item>

            <Form.Item
              name="namespace"
              label={t("instance.namespace")}
              rules={[{ required: true, message: t("common.required") }]}
            >
              <Input placeholder={t("instance.namespace_placeholder")} />
            </Form.Item>

            <Form.Item name="createNamespace" label={t("instance.create_namespace")}>
              <Select>
                <Option value={true}>{t("common.yes")}</Option>
                <Option value={false}>{t("common.no")}</Option>
              </Select>
            </Form.Item>

            <Form.Item
              name="specType"
              label={t("instance.spec_type")}
              rules={[{ required: true }]}
            >
              <Select>
                {specs.map((spec) => (
                  <Option key={spec.type} value={spec.type}>
                    {spec.description}
                  </Option>
                ))}
              </Select>
            </Form.Item>

            {selectedSpecType === "custom" && (
              <Space style={{ width: "100%" }} size="large">
                <Form.Item
                  name="cpuCores"
                  label={t("instance.cpu_cores")}
                  rules={[{ required: true, message: t("common.required") }]}
                  style={{ width: "200px", marginBottom: 0 }}
                >
                  <InputNumber
                    min={0.1}
                    max={16}
                    step={0.5}
                    style={{ width: "100%" }}
                    placeholder="1.0"
                  />
                </Form.Item>
                <Form.Item
                  name="memoryMB"
                  label={t("instance.memory_mb")}
                  rules={[{ required: true, message: t("common.required") }]}
                  style={{ width: "200px", marginBottom: 0 }}
                >
                  <InputNumber
                    min={128}
                    max={16384}
                    step={128}
                    style={{ width: "100%" }}
                    placeholder="1024"
                  />
                </Form.Item>
              </Space>
            )}

            <Form.Item
              name="replicas"
              label={t("instance.replicas")}
              rules={[{ required: true }]}
            >
              <InputNumber min={1} max={10} style={{ width: "200px" }} />
            </Form.Item>

            <Form.Item
              name="imagePullPolicy"
              label={t("instance.image_pull_policy")}
              tooltip={t("instance.image_pull_policy_tooltip")}
            >
              <Select style={{ width: "300px" }}>
                <Option value="IfNotPresent">{t("instance.pull_if_not_present")}</Option>
                <Option value="Always">{t("instance.pull_always")}</Option>
                <Option value="Never">{t("instance.pull_never")}</Option>
              </Select>
            </Form.Item>

            <Form.Item
              name="image"
              label={t("instance.image")}
              tooltip={t("instance.image_tooltip")}
            >
              <Input placeholder="e.g., my-gateway:latest" style={{ width: "300px" }} />
            </Form.Item>

            <Form.Item name="description" label={t("instance.description")}>
              <TextArea rows={3} />
            </Form.Item>

            <Form.Item style={{ marginBottom: 0, marginTop: "24px" }}>
              <Space>
                <Button onClick={handleCancel}>{t("common.cancel")}</Button>
                <Button type="primary" htmlType="submit" loading={submitting}>
                  {t("instance.create_and_deploy")}
                </Button>
              </Space>
            </Form.Item>
          </Form>
        </Spin>
      </Card>
    </div>
  );
};

export default InstanceCreatePage;